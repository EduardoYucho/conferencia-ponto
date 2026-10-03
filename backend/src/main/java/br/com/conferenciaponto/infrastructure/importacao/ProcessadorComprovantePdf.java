package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.application.evento.ComprovanteNaoImportadoEvento;
import org.springframework.context.ApplicationEventPublisher;
import br.com.conferenciaponto.application.usecase.ImportarComprovanteUseCase;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orquestra a importação de um PDF: espera o download terminar (tamanho estável e
 * arquivo destravado), lê com trava via {@link PdfParserService} e entrega a
 * data/hora ao caso de uso, que aloca a batida e notifica o front-end.
 */
@Component
public class ProcessadorComprovantePdf {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorComprovantePdf.class);

    /**
     * Arquivo sem alteração há esse tempo já terminou de ser gravado: dispensa a espera de
     * estabilização (importante na primeira leitura de uma pasta com centenas de PDFs antigos).
     */
    static final Duration ARQUIVO_ASSENTADO = Duration.ofSeconds(30);
    /** Arquivo vazio há esse tempo não é mais um download em andamento. */
    static final Duration ARQUIVO_ABANDONADO = Duration.ofMinutes(2);

    private final PdfParserService parser;
    private final ImportarComprovanteUseCase importar;
    private final ImportacaoPdfProperties properties;
    private final ApplicationEventPublisher eventos;

    public ProcessadorComprovantePdf(PdfParserService parser, ImportarComprovanteUseCase importar,
                                     ImportacaoPdfProperties properties, ApplicationEventPublisher eventos) {
        this.parser = parser;
        this.importar = importar;
        this.properties = properties;
        this.eventos = eventos;
    }

    /** Processador da pasta de um usuário (usado pelo monitor dele). */
    public ProcessadorDeComprovantes doUsuario(UUID usuarioId) {
        return arquivo -> processar(usuarioId, arquivo);
    }

    /** Resultado de um PDF enviado pela tela. {@code JA_PROCESSADO}: o mesmo arquivo já tinha sido lido. */
    public record ResultadoEnvio(String nomeArquivo, String status, String mensagem, LocalDateTime dataHoraBatida) {
    }

    /** PDF enviado pela tela ("Enviar comprovantes"): mesmas regras da pasta monitorada. */
    public ResultadoEnvio enviar(UUID usuarioId, String nomeArquivo, byte[] conteudo) {
        String nome = nomeSimples(nomeArquivo);
        PdfParserService.ComprovanteLido lido;
        try {
            lido = parser.lerConteudo(nome, conteudo);
        } catch (IOException e) {
            // as mensagens do leitor são feitas para a pessoa (arquivo vazio, grande demais)
            return new ResultadoEnvio(nome, StatusImportacao.INVALIDO.name(), e.getMessage(), null);
        } catch (RuntimeException e) {
            log.warn("PDF \"{}\" enviado pela tela não pôde ser lido: {}", nome, e.toString());
            return new ResultadoEnvio(nome, StatusImportacao.INVALIDO.name(),
                    "O arquivo não é um PDF válido ou está danificado.", null);
        }
        synchronized (this) {
            return importar.executar(usuarioId, new ImportarComprovanteUseCase.Comprovante(
                            lido.nomeArquivo(), lido.hashSha256(), lido.dataHora(), lido.conteudo()))
                    .map(r -> new ResultadoEnvio(nome, r.status().name(), r.mensagem(), lido.dataHora().orElse(null)))
                    .orElseGet(() -> new ResultadoEnvio(nome, "JA_PROCESSADO",
                            "Este comprovante já tinha sido processado.", lido.dataHora().orElse(null)));
        }
    }

    /** Só o nome do arquivo, sem pasta (o nome vem do navegador e pode ter caracteres que o Windows não aceita). */
    static String nomeSimples(String nomeArquivo) {
        if (nomeArquivo == null || nomeArquivo.isBlank()) {
            return "comprovante.pdf";
        }
        String nome = nomeArquivo.substring(Math.max(nomeArquivo.lastIndexOf('/'), nomeArquivo.lastIndexOf('\\')) + 1).strip();
        return nome.isEmpty() ? "comprovante.pdf" : nome.length() > 200 ? nome.substring(nome.length() - 200) : nome;
    }

    /**
     * {@code synchronized}: os monitores (um por usuário), o envio pela tela e o reprocessamento manual
     * podem disparar ao mesmo tempo; processar um arquivo por vez evita duas transações alterando o mesmo dia.
     */
    public synchronized boolean processar(UUID usuarioId, Path arquivo) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                aguardarTamanhoEstavel(arquivo);
                PdfParserService.ComprovanteLido lido = parser.ler(arquivo);
                importar.executar(usuarioId, new ImportarComprovanteUseCase.Comprovante(
                                lido.nomeArquivo(), lido.hashSha256(), lido.dataHora(), lido.conteudo()))
                        .ifPresentOrElse(
                                r -> log.info("[{}] {}: {}", r.status(), lido.nomeArquivo(), r.mensagem()),
                                () -> log.debug("{} já processado anteriormente", lido.nomeArquivo()));
                return true;
            } catch (NoSuchFileException e) {
                log.debug("{} não existe mais (arquivo temporário?)", arquivo.getFileName());
                return true;
            } catch (ArquivoRecusadoException e) {
                // definitivo: avisa a pessoa uma vez e não tenta de novo enquanto o arquivo não mudar
                log.warn("{} não foi importado: {}", arquivo.getFileName(), e.getMessage());
                avisarRecusa(usuarioId, arquivo, e.getMessage());
                return true;
            } catch (IOException e) {
                Path pasta = arquivo.getParent();
                if (pasta != null && !Files.isDirectory(pasta)) {
                    // a pasta (de rede) caiu: não adianta insistir agora, o monitor reconecta e tenta de novo
                    log.debug("Pasta de {} inacessível: {}", arquivo.getFileName(), e.getMessage());
                    return false;
                }
                if (tentativa >= properties.tentativasLeitura()) {
                    log.warn("Não foi possível ler {} após {} tentativas ({}); nova tentativa na próxima varredura",
                            arquivo.getFileName(), tentativa, e.getMessage());
                    return false;
                }
                log.debug("Tentativa {} para {}: {}", tentativa, arquivo.getFileName(), e.getMessage());
                dormir(properties.intervaloTentativa());
            } catch (RuntimeException e) {
                // ex.: banco fora do ar. Não marca como tratado: a próxima varredura tenta de novo.
                log.error("Falha inesperada ao importar {}", arquivo, e);
                return false;
            }
        }
    }

    private void avisarRecusa(UUID usuarioId, Path arquivo, String mensagem) {
        try {
            eventos.publishEvent(new ComprovanteNaoImportadoEvento(usuarioId, String.valueOf(arquivo.getFileName()),
                    StatusImportacao.INVALIDO, null, mensagem));
        } catch (RuntimeException e) {
            log.debug("Aviso de arquivo recusado não entregue: {}", e.getMessage());
        }
    }

    /** O navegador pode criar o arquivo antes de terminar de gravar: exige tamanho > 0 e estável. */
    private void aguardarTamanhoEstavel(Path arquivo) throws IOException {
        long antes = Files.size(arquivo);
        Instant modificadoEm = Files.getLastModifiedTime(arquivo).toInstant();
        if (antes == 0 && modificadoEm.isBefore(Instant.now().minus(ARQUIVO_ABANDONADO))) {
            throw new ArquivoRecusadoException("O arquivo está vazio (download interrompido). Baixe o comprovante de novo.");
        }
        if (antes > 0 && modificadoEm.isBefore(Instant.now().minus(ARQUIVO_ASSENTADO))) {
            return;
        }
        dormir(properties.estabilizacao());
        long depois = Files.size(arquivo);
        if (antes == 0 || antes != depois) {
            throw new ArquivoEmUsoException(arquivo, "tamanho ainda mudando (%d -> %d bytes)".formatted(antes, depois));
        }
    }

    private static void dormir(Duration duracao) {
        try {
            Thread.sleep(duracao.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
