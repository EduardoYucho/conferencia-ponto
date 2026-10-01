package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor.Situacao;
import br.com.conferenciaponto.infrastructure.importacao.ProcessadorDeComprovantes.ArquivoPdf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Monitora a pasta dos comprovantes de um usuário e dispara a importação de cada PDF novo. Cada usuário com
 * pasta configurada tem o seu monitor, numa thread própria (ver {@link GerenciadorMonitoresPdf}).
 *
 * <p>Pensado para funcionar também com <b>pasta de rede</b> (ex.: {@code \\servidor\Ponto} via VPN):
 * <ul>
 *   <li><b>Avisos do sistema operacional</b> ({@link WatchService}, {@code ENTRY_CREATE}): importação
 *       imediata. Navegadores baixam para .crdownload/.part e renomeiam no fim; o rename gera o aviso
 *       com o nome final.</li>
 *   <li><b>Varredura periódica</b>: em pasta de rede os avisos podem se perder (queda da conexão,
 *       estouro do buffer do SMB). A cada {@code varreduraPeriodica} a pasta é listada e só os
 *       arquivos novos ou alterados (nome + tamanho + data) são lidos.</li>
 *   <li><b>Reconexão</b>: se a pasta ficar inacessível, o monitor passa a {@link Situacao#INDISPONIVEL},
 *       tenta de novo a cada {@code intervaloReconexao} e, ao voltar, confere o que chegou nesse meio-tempo.</li>
 * </ul>
 * A deduplicação por hash no caso de uso garante que reler um arquivo nunca duplica batidas.
 */
public class DiretorioPontoWatcher {

    private static final Logger log = LoggerFactory.getLogger(DiretorioPontoWatcher.class);

    /** Falhas seguidas numa varredura antes de desistir dela (ex.: banco fora do ar). */
    private static final int FALHAS_PARA_INTERROMPER_VARREDURA = 3;
    /** Sem varredura periódica, o laço ainda acorda nesse intervalo para checar a parada. */
    private static final Duration ESPERA_MAXIMA_SEM_VARREDURA = Duration.ofMinutes(1);

    private final UUID usuarioId;
    private final Path diretorio;
    private final boolean criarPasta;
    private final boolean processarExistentes;
    private final Duration varreduraPeriodica;
    private final Duration intervaloReconexao;
    private final ProcessadorDeComprovantes processador;
    private final ApplicationEventPublisher eventos;

    /** Arquivos já tratados nesta execução: evita reler pela rede o que não mudou. */
    private final Map<String, Assinatura> tratados = new ConcurrentHashMap<>();
    private final CountDownLatch parada = new CountDownLatch(1);

    private volatile WatchService watchService;
    private volatile EstadoMonitor estado;

    private record Assinatura(long tamanho, long modificadoEm) {
    }

    /** Monitor de teste/uso local: cria a pasta se ela não existir. */
    public DiretorioPontoWatcher(Path diretorio, boolean processarExistentes, Duration varreduraPeriodica,
                                 Duration intervaloReconexao, ProcessadorDeComprovantes processador,
                                 ApplicationEventPublisher eventos) {
        this(null, diretorio, true, processarExistentes, varreduraPeriodica, intervaloReconexao, processador, eventos);
    }

    /**
     * @param criarPasta cria a pasta se ela não existir (a pasta escolhida pelo usuário não é criada: se não
     *                   existir, o monitor fica INDISPONIVEL com "pasta não encontrada")
     */
    public DiretorioPontoWatcher(UUID usuarioId, Path diretorio, boolean criarPasta, boolean processarExistentes,
                                 Duration varreduraPeriodica, Duration intervaloReconexao,
                                 ProcessadorDeComprovantes processador, ApplicationEventPublisher eventos) {
        this.usuarioId = usuarioId;
        this.diretorio = diretorio;
        this.criarPasta = criarPasta;
        this.processarExistentes = processarExistentes;
        this.varreduraPeriodica = varreduraPeriodica;
        this.intervaloReconexao = intervaloReconexao;
        this.processador = processador;
        this.eventos = eventos;
        this.estado = new EstadoMonitor(usuarioId, Situacao.INICIANDO, diretorio.toString(), null, Instant.now(), null);
    }

    /** Laço bloqueante de monitoramento; termina em {@link #parar()}. */
    public void monitorar() {
        boolean primeiraLeitura = true;
        try {
            while (!parado()) {
                try {
                    conectar();
                    ficarAtivo();
                    if (primeiraLeitura && !processarExistentes) {
                        registrarExistentesSemProcessar();
                    } else {
                        varrer();
                    }
                    primeiraLeitura = false;
                    lacoDeEventos();
                } catch (IOException | RuntimeException e) {
                    fecharWatchService();
                    if (parado()) {
                        break;
                    }
                    ficarIndisponivel(e);
                    aguardar(intervaloReconexao);
                }
            }
        } finally {
            fecharWatchService();
            mudarPara(Situacao.ENCERRADO, null);
            log.info("Monitor de comprovantes encerrado");
        }
    }

    // ------------------------------------------------------------------ conexão

    /** Registra o aviso de novos arquivos ANTES de listar a pasta, para não perder nada no intervalo. */
    private void conectar() throws IOException {
        if (!Files.isDirectory(diretorio)) {
            if (!criarPasta) {
                throw new NoSuchFileException(diretorio.toString());
            }
            Files.createDirectories(diretorio); // pasta local nova; em pasta de rede fora do ar, falha aqui
        }
        WatchService servico = diretorio.getFileSystem().newWatchService();
        try {
            diretorio.register(servico, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException | RuntimeException e) {
            servico.close();
            throw e;
        }
        watchService = servico;
    }

    private void lacoDeEventos() throws IOException {
        WatchService servico = watchService;
        if (servico == null) {
            return; // parado enquanto conectava
        }
        boolean comVarredura = !varreduraPeriodica.isZero() && !varreduraPeriodica.isNegative();
        long proximaVarredura = System.nanoTime() + varreduraPeriodica.toNanos();

        while (!parado()) {
            long espera = comVarredura
                    ? Math.max(0, proximaVarredura - System.nanoTime())
                    : ESPERA_MAXIMA_SEM_VARREDURA.toNanos();
            WatchKey chave;
            try {
                chave = servico.poll(espera, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                parar();
                return;
            } catch (ClosedWatchServiceException e) {
                if (parado()) {
                    return;
                }
                throw new IOException("o monitoramento da pasta foi interrompido", e);
            }

            if (chave != null) {
                tratarEventos(chave);
                if (!chave.reset()) {
                    throw new IOException("a pasta deixou de estar acessível");
                }
            }
            if (comVarredura && System.nanoTime() - proximaVarredura >= 0) {
                varrer();
                proximaVarredura = System.nanoTime() + varreduraPeriodica.toNanos();
            }
        }
    }

    private void tratarEventos(WatchKey chave) throws IOException {
        for (WatchEvent<?> evento : chave.pollEvents()) {
            if (evento.kind() == StandardWatchEventKinds.OVERFLOW) {
                log.warn("Eventos perdidos (OVERFLOW); conferindo a pasta inteira");
                varrer();
                continue;
            }
            Path arquivo = diretorio.resolve((Path) evento.context());
            if (ProcessadorDeComprovantes.ehPdf(arquivo)) {
                log.debug("Novo arquivo detectado: {}", arquivo.getFileName());
                Assinatura atual = assinaturaAtual(arquivo);
                if (atual != null) {
                    processarSeNovo(arquivo, atual);
                }
            }
        }
    }

    // ------------------------------------------------------------------ varredura

    /** Confere a pasta inteira e processa o que for novo ou tiver mudado desde a última leitura. */
    private void varrer() throws IOException {
        int falhasSeguidas = 0;
        for (ArquivoPdf pdf : ProcessadorDeComprovantes.listarPdfs(diretorio)) {
            if (parado()) {
                return;
            }
            boolean tratado = processarSeNovo(pdf.caminho(), new Assinatura(pdf.tamanho(), pdf.modificadoEm()));
            falhasSeguidas = tratado ? 0 : falhasSeguidas + 1;
            if (falhasSeguidas >= FALHAS_PARA_INTERROMPER_VARREDURA) {
                if (!Files.isDirectory(diretorio)) {
                    throw new IOException("a pasta ficou inacessível durante a leitura");
                }
                log.warn("{} arquivos seguidos não puderam ser importados; nova tentativa na próxima varredura",
                        falhasSeguidas);
                break;
            }
        }
        estado = estado.comVarredura(Instant.now());
    }

    /** Com {@code processarExistentes=false}: o que já estava na pasta é dado como tratado. */
    private void registrarExistentesSemProcessar() throws IOException {
        for (ArquivoPdf pdf : ProcessadorDeComprovantes.listarPdfs(diretorio)) {
            tratados.put(nome(pdf.caminho()), new Assinatura(pdf.tamanho(), pdf.modificadoEm()));
        }
        estado = estado.comVarredura(Instant.now());
    }

    /** @return {@code false} só quando o arquivo precisa ser tentado de novo depois */
    private boolean processarSeNovo(Path arquivo, Assinatura atual) {
        String nome = nome(arquivo);
        if (atual.equals(tratados.get(nome))) {
            return true;
        }
        if (!processador.processar(arquivo)) {
            return false;
        }
        Assinatura depois = assinaturaAtual(arquivo); // o download pode ter terminado durante a leitura
        tratados.put(nome, depois != null ? depois : atual);
        return true;
    }

    private static Assinatura assinaturaAtual(Path arquivo) {
        try {
            BasicFileAttributes atributos = Files.readAttributes(arquivo, BasicFileAttributes.class);
            return atributos.isRegularFile()
                    ? new Assinatura(atributos.size(), atributos.lastModifiedTime().toMillis())
                    : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.debug("Não foi possível ler os atributos de {}: {}", arquivo.getFileName(), e.getMessage());
            return null;
        }
    }

    private static String nome(Path arquivo) {
        return arquivo.getFileName().toString();
    }

    // ------------------------------------------------------------------ estado

    private void ficarAtivo() {
        Situacao anterior = estado.situacao();
        if (anterior == Situacao.INDISPONIVEL) {
            log.info("Pasta {} acessível novamente; conferindo arquivos que chegaram nesse intervalo", diretorio);
        } else {
            log.info("Monitorando comprovantes de ponto em {} (conferência a cada {}s)",
                    diretorio, varreduraPeriodica.toSeconds());
        }
        mudarPara(Situacao.ATIVO, null);
    }

    private void ficarIndisponivel(Exception erro) {
        String motivo = descrever(erro);
        if (estado.situacao() != Situacao.INDISPONIVEL) {
            log.warn("Pasta {} inacessível ({}). Nova tentativa a cada {}s.",
                    diretorio, motivo, intervaloReconexao.toSeconds());
        } else {
            log.debug("Pasta {} continua inacessível: {}", diretorio, motivo);
        }
        mudarPara(Situacao.INDISPONIVEL, motivo);
    }

    private void mudarPara(Situacao situacao, String mensagem) {
        EstadoMonitor atual = estado;
        if (atual.situacao() == situacao && Objects.equals(atual.mensagem(), mensagem)) {
            return;
        }
        EstadoMonitor novo = new EstadoMonitor(usuarioId, situacao, diretorio.toString(), mensagem, Instant.now(),
                atual.ultimaVarredura());
        estado = novo;
        try {
            eventos.publishEvent(novo);
        } catch (RuntimeException e) {
            log.debug("Falha ao publicar o estado do monitor: {}", e.getMessage());
        }
    }

    private static String descrever(Exception erro) {
        String mensagem = erro.getMessage();
        if (erro instanceof NoSuchFileException) {
            return "pasta não encontrada";
        }
        return mensagem == null || mensagem.isBlank() ? erro.getClass().getSimpleName() : mensagem;
    }

    // ------------------------------------------------------------------ ciclo de vida

    private boolean parado() {
        return parada.getCount() == 0;
    }

    private void aguardar(Duration duracao) {
        try {
            parada.await(duracao.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            parar();
        }
    }

    private void fecharWatchService() {
        WatchService servico = watchService;
        watchService = null;
        if (servico != null) {
            try {
                servico.close();
            } catch (IOException e) {
                log.debug("Erro ao fechar WatchService: {}", e.getMessage());
            }
        }
    }

    public void parar() {
        parada.countDown();
        fecharWatchService();
    }

    public EstadoMonitor getEstado() {
        return estado;
    }

    public boolean isAtivo() {
        return estado.ativo();
    }

    public Path getDiretorio() {
        return diretorio;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }
}
