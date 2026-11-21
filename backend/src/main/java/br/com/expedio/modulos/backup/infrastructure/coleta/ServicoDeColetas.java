package br.com.expedio.modulos.backup.infrastructure.coleta;

import br.com.expedio.application.evento.AvisoDeModulo;
import br.com.expedio.modulos.backup.domain.Cliente;
import br.com.expedio.modulos.backup.domain.Coleta;
import br.com.expedio.modulos.backup.domain.Coleta.Situacao;
import br.com.expedio.modulos.backup.domain.ErroDeColeta;
import br.com.expedio.modulos.backup.domain.NomesSeguros;
import br.com.expedio.modulos.backup.domain.Pedidos;
import br.com.expedio.modulos.backup.domain.Registro;
import br.com.expedio.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.luben.zstd.Zstd;

import jakarta.annotation.PostConstruct;

/**
 * O repasse de uma coleta, do navegador do cliente para o navegador de quem gerou o link. <b>O backup não é gravado
 * neste computador</b>: cada pedaço (um quadro zstd independente) fica na memória só até o navegador de quem recebe
 * confirmar que gravou na pasta dele; só então a página do cliente recebe o "ok" e apaga a cópia dela.
 *
 * <pre>
 * cliente  PUT pedaço n  ──►  memória  ──►  GET receber (quem recebe)  ──►  grava na pasta escolhida
 *          ◄── ok ───────────────────────  POST recebido n  ◄────────────  (confere o SHA-256)
 * </pre>
 *
 * <p>Sem quem recebe com a tela aberta, o pedaço é recusado ({@code RECEPTOR_AUSENTE}) e espera no navegador do
 * cliente. Aqui ficam só os dados de controle: cada pedaço confirmado vira uma linha em blocos.jsonl (onde a leitura
 * parou, os arquivos do bloco com o SHA-256), o que permite retomar e, no fim, montar o manifesto, entregue como
 * último pedaço. Quem recebe monta o .tar.zst, confere cada arquivo e informa o resultado; o aviso vai para o sino.
 */
@Service
public class ServicoDeColetas {

    /** A tela que o aviso no sino abre. */
    public static final String TELA = "/backups-clientes";

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeColetas.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long MAX_ADIANTADO = 8;
    private static final long MAX_NA_MEMORIA = 96L * 1024 * 1024;
    private static final DateTimeFormatter NOME_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final Duration JANELA_VELOCIDADE = Duration.ofSeconds(60);
    /** Quem recebe que não pede pedaço há mais que isto está com a tela fechada. */
    static final Duration RECEPTOR_PRESENTE = Duration.ofSeconds(30);
    /** Quanto a página do cliente espera, numa requisição, a confirmação de quem recebe. */
    static final Duration ESPERA_GRAVACAO = Duration.ofSeconds(45);

    /** Um pedaço na memória, esperando quem recebe gravar. */
    private record Pendente(long seq, byte[] quadro, JsonNode cabecalho, CompletableFuture<Void> gravado) {
    }

    /** O que está de passagem numa coleta (só memória). */
    private static final class Repasse {
        final TreeMap<Long, Pendente> pendentes = new TreeMap<>();
        long bytes;
        volatile long contatoDoReceptor;
        byte[] parteFinal;
    }

    private final ConfiguracaoDoBackup configuracao;
    private final RepositorioDeColetas repositorio;
    private final RepositorioDeClientes clientes;
    private final ObjectMapper json;
    private final ApplicationEventPublisher eventos;
    private final SecureRandom aleatorio = new SecureRandom();

    private final Map<String, Coleta> porId = new ConcurrentHashMap<>();
    private final Map<String, String> idPorToken = new ConcurrentHashMap<>();
    private final Map<String, Object> travas = new ConcurrentHashMap<>();
    private final Map<String, Repasse> repasses = new ConcurrentHashMap<>();
    private final Map<String, Deque<long[]>> amostras = new ConcurrentHashMap<>();

    public ServicoDeColetas(ConfiguracaoDoBackup configuracao, RepositorioDeColetas repositorio,
            RepositorioDeClientes clientes, ObjectMapper json, ApplicationEventPublisher eventos) {
        this.configuracao = configuracao;
        this.repositorio = repositorio;
        this.clientes = clientes;
        this.json = json;
        this.eventos = eventos;
    }

    @PostConstruct
    void carregar() {
        for (Coleta coleta : repositorio.carregarTodas()) {
            porId.put(coleta.id, coleta);
            idPorToken.put(coleta.token, coleta.id);
        }
        LOG.info("Backups de clientes: {} coleta(s) no histórico", porId.size());
    }

    private Object trava(String id) {
        return travas.computeIfAbsent(id, k -> new Object());
    }

    private Repasse repasse(String id) {
        return repasses.computeIfAbsent(id, k -> new Repasse());
    }

    // ------------------------------------------------------------------ tela (quem está logado no sistema)

    /** Um link novo para o cliente. Quem gera é quem recebe (no navegador dele) e recebe o aviso no sino. */
    public Coleta criar(String nomeCliente, UUID usuarioId, String login) {
        Cliente cliente = clientes.buscar(nomeCliente)
                .orElseThrow(() -> new ErroDeColeta(404, "CLIENTE_DESCONHECIDO", "Cliente não cadastrado: " + nomeCliente));
        Instant agora = Instant.now();
        Coleta coleta = new Coleta();
        String data = LocalDateTime.ofInstant(agora, ZoneId.systemDefault()).format(NOME_DATA);
        coleta.id = data.replace("_", "-") + "-" + HexFormat.of().formatHex(bytesAleatorios(2));
        coleta.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytesAleatorios(32));
        coleta.cliente = cliente.nome();
        coleta.nome = NomesSeguros.pastaDoCliente(cliente.nome()) + "_" + data;
        coleta.criadaEm = agora;
        coleta.expiraEm = agora.plus(validade());
        coleta.situacao = Situacao.AGUARDANDO;
        coleta.criadaPorId = usuarioId;
        coleta.criadaPor = login;
        repositorio.salvar(coleta);
        porId.put(coleta.id, coleta);
        idPorToken.put(coleta.token, coleta.id);
        repasse(coleta.id).contatoDoReceptor = System.currentTimeMillis();
        LOG.info("Coleta {} criada para {} por {}", coleta.id, coleta.cliente, login);
        return coleta;
    }

    public Collection<Coleta> listar() {
        return porId.values().stream().sorted(Comparator.comparing((Coleta c) -> c.criadaEm).reversed()).toList();
    }

    public Coleta buscar(String id) {
        Coleta coleta = porId.get(id);
        if (coleta == null) {
            throw new ErroDeColeta(404, "COLETA_DESCONHECIDA", "Coleta não encontrada.");
        }
        return coleta;
    }

    /** O navegador de quem recebe pediu pedaço há pouco (a tela dele está aberta). */
    public boolean receptorOnline(String id) {
        Repasse r = repasses.get(id);
        return r != null && System.currentTimeMillis() - r.contatoDoReceptor < RECEPTOR_PRESENTE.toMillis();
    }

    /** Para os testes: como se a tela de quem recebe tivesse sido fechada há tempo. */
    void esquecerReceptor(String id) {
        repasse(id).contatoDoReceptor = 0;
    }

    /** Velocidade de entrega (bytes/s) no último minuto. */
    public double velocidade(String id) {
        Deque<long[]> fila = amostras.get(id);
        if (fila == null) {
            return 0;
        }
        synchronized (fila) {
            if (fila.size() < 2) {
                return 0;
            }
            long[] primeira = fila.peekFirst();
            long[] ultima = fila.peekLast();
            long agora = System.currentTimeMillis();
            if (agora - ultima[0] > 30_000) {
                return 0;
            }
            double segundos = Math.max(1, (agora - primeira[0]) / 1000.0);
            return (ultima[1] - primeira[1]) / segundos;
        }
    }

    public void cancelar(String id) {
        Coleta coleta = buscar(id);
        synchronized (trava(id)) {
            if (!coleta.situacao.ativa()) {
                throw new ErroDeColeta(409, "NAO_CANCELAVEL", "Só dá para cancelar uma coleta em andamento.");
            }
            coleta.situacao = Situacao.CANCELADA;
            coleta.mensagem = "Cancelada na tela Backups de clientes.";
            descartarPendentes(coleta.id, new ErroDeColeta(410, "CANCELADA", "Esta coleta foi cancelada no sistema."));
            repositorio.limparTrabalho(coleta);
            repositorio.salvar(coleta);
            trava(id).notifyAll();
        }
    }

    /** Tira do histórico (o backup, no computador de quem recebeu, não é tocado). */
    public void apagar(String id) {
        Coleta coleta = buscar(id);
        synchronized (trava(id)) {
            if (coleta.situacao.ativa()) {
                throw new ErroDeColeta(409, "EM_ANDAMENTO", "Cancele a coleta antes de tirá-la do histórico.");
            }
            try {
                repositorio.apagarColeta(coleta);
            } catch (IOException e) {
                throw new ErroDeColeta(500, "FALHA_AO_APAGAR", "Não consegui apagar: " + e.getMessage());
            }
            porId.remove(id);
            idPorToken.remove(coleta.token);
            amostras.remove(id);
            repasses.remove(id);
            LOG.info("Coleta {} tirada do histórico", id);
        }
    }

    // ------------------------------------------------------------------ navegador de quem recebe

    private Coleta doReceptor(String id, UUID usuarioId) {
        Coleta coleta = buscar(id);
        if (coleta.criadaPorId != null && !coleta.criadaPorId.equals(usuarioId)) {
            throw new ErroDeColeta(403, "OUTRO_RECEPTOR", "Este backup vai para o computador de quem gerou o link ("
                    + coleta.criadaPor + ").");
        }
        return coleta;
    }

    /**
     * O próximo pedaço para gravar, esperando até {@code espera} ele chegar; vazio se não chegou. Marca a tela de quem
     * recebe como aberta.
     */
    public Optional<Pedidos.Entrega> proximaParte(String id, UUID usuarioId, Duration espera) {
        Coleta coleta = doReceptor(id, usuarioId);
        Repasse r = repasse(id);
        long limite = System.currentTimeMillis() + espera.toMillis();
        Object trava = trava(id);
        synchronized (trava) {
            while (true) {
                r.contatoDoReceptor = System.currentTimeMillis();
                if (!coleta.situacao.recebendo()) {
                    return Optional.empty();
                }
                Pendente p = r.pendentes.get(coleta.proximoSeq);
                if (p != null) {
                    return Optional.of(new Pedidos.Entrega(p.seq(), p.quadro(), Envelope.sha256(p.quadro())));
                }
                long falta = limite - System.currentTimeMillis();
                if (falta <= 0) {
                    return Optional.empty();
                }
                try {
                    trava.wait(Math.min(falta, 5_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
    }

    /** Quem recebe gravou o pedaço {@code seq} (com este SHA-256): ele entra no andamento e a página do cliente é liberada. */
    public Pedidos.ParteAceita confirmarParte(String id, UUID usuarioId, long seq, String sha256) {
        Coleta coleta = doReceptor(id, usuarioId);
        Repasse r = repasse(id);
        synchronized (trava(id)) {
            r.contatoDoReceptor = System.currentTimeMillis();
            if (seq < coleta.proximoSeq) {
                return new Pedidos.ParteAceita(coleta.proximoSeq, coleta.bytesArquivo);
            }
            Pendente p = r.pendentes.get(seq);
            if (seq != coleta.proximoSeq || p == null) {
                throw new ErroDeColeta(409, "PEDACO_FORA_DE_ORDEM", "Este pedaço não é o esperado agora; peça o próximo de novo.");
            }
            if (!Envelope.sha256(p.quadro()).equalsIgnoreCase(sha256 == null ? "" : sha256)) {
                throw new ErroDeColeta(422, "GRAVADO_DIFERENTE", "O pedaço gravado não confere; peça de novo.");
            }
            JsonNode cab = p.cabecalho();
            Registro registro = new Registro(seq, coleta.bytesArquivo + p.quadro().length, cab.path("bruto").asLong(0),
                    cab.get("fim"), cab.get("arquivos"), cab.get("naoLidos"));
            repositorio.anexarRegistro(coleta, registro);
            r.pendentes.remove(seq);
            r.bytes -= p.quadro().length;
            coleta.proximoSeq++;
            coleta.bytesArquivo = registro.offsetFinal();
            coleta.cursor = registro.fim();
            coleta.brutoRecebido += registro.bruto();
            coleta.arquivosRecebidos += registro.arquivos() == null ? 0 : registro.arquivos().size();
            amostrar(coleta);
            p.gravado().complete(null);
            trava(id).notifyAll();
            return new Pedidos.ParteAceita(coleta.proximoSeq, coleta.bytesArquivo);
        }
    }

    /**
     * O último pedaço, montado aqui só com os dados de controle: o manifesto (o SHA-256 de cada arquivo, calculado no
     * cliente) e o fim do .tar, compactados num quadro zstd. Sempre o mesmo para a mesma coleta.
     */
    public byte[] parteFinal(String id, UUID usuarioId) {
        Coleta coleta = doReceptor(id, usuarioId);
        Repasse r = repasse(id);
        synchronized (trava(id)) {
            r.contatoDoReceptor = System.currentTimeMillis();
            if (coleta.situacao != Situacao.FECHANDO) {
                throw new ErroDeColeta(409, "AINDA_CHEGANDO", "O backup ainda não chegou inteiro.");
            }
            if (r.parteFinal == null) {
                try {
                    r.parteFinal = montarParteFinal(coleta);
                    repositorio.salvar(coleta);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return r.parteFinal;
        }
    }

    /** Quem recebe montou o .tar.zst e conferiu: o resultado vai para o histórico e o aviso para o sino. */
    public Coleta registrarResultado(String id, UUID usuarioId, Pedidos.Resultado resultado) {
        Coleta coleta = doReceptor(id, usuarioId);
        synchronized (trava(id)) {
            if (coleta.situacao != Situacao.FECHANDO) {
                throw new ErroDeColeta(409, "NAO_ESTA_FECHANDO", "Esta coleta não está esperando a conferência.");
            }
            if (coleta.resumo == null) {
                parteFinal(id, usuarioId);
            }
            Instant agora = Instant.now();
            coleta.conferencia = new Coleta.Conferencia(resultado.integra() ? "INTEGRA" : "DIVERGENTE", agora,
                    resultado.arquivosConferidos(), resultado.bytesConferidos(), limitar(resultado.divergentes()),
                    limitar(resultado.faltando()), limitar(resultado.sobrando()), limitar(resultado.mensagem(), 500));
            coleta.tamanhoArquivo = resultado.tamanhoArquivo();
            coleta.conferidaEm = agora;
            coleta.situacao = resultado.integra() ? Situacao.INTEGRA : Situacao.DIVERGENTE;
            coleta.mensagem = coleta.conferencia.mensagem();
            repositorio.salvar(coleta);
            repositorio.limparTrabalho(coleta);
            repasses.remove(id);
            trava(id).notifyAll();
        }
        LOG.info("Coleta {}: {}", coleta.id, coleta.mensagem);
        avisar(coleta);
        return coleta;
    }

    private byte[] montarParteFinal(Coleta coleta) throws IOException {
        List<Registro> registros = repositorio.lerRegistros(coleta);
        ObjectNode manifesto = json.createObjectNode();
        manifesto.put("formato", "backup-clientes/1");
        manifesto.put("coleta", coleta.id);
        manifesto.put("cliente", coleta.cliente);
        manifesto.put("raiz", coleta.raiz);
        manifesto.set("pastas", json.valueToTree(coleta.pastas));
        manifesto.put("computador", coleta.computador);
        manifesto.put("navegador", coleta.navegador);
        manifesto.put("inicio", String.valueOf(coleta.inicioEnvio));
        manifesto.put("fim", String.valueOf(coleta.concluidaEm));
        ArrayNode arquivos = manifesto.putArray("arquivos");
        ArrayNode naoLidos = manifesto.putArray("naoLidos");
        long nArquivos = 0;
        long nPastas = 0;
        long bytesOriginais = 0;
        List<String> alterados = new ArrayList<>();
        List<String> listaNaoLidos = new ArrayList<>();
        for (Registro registro : registros) {
            if (registro.arquivos() != null) {
                for (JsonNode item : registro.arquivos()) {
                    arquivos.add(item);
                    if ("pasta".equals(item.path("tipo").asText())) {
                        nPastas++;
                    } else {
                        nArquivos++;
                        bytesOriginais += item.path("tamanho").asLong(0);
                        if (item.path("alterado").asBoolean(false)) {
                            alterados.add(item.path("caminho").asText());
                        }
                    }
                }
            }
            if (registro.naoLidos() != null) {
                for (JsonNode item : registro.naoLidos()) {
                    naoLidos.add(item);
                    listaNaoLidos.add(item.path("caminho").asText() + " — " + item.path("motivo").asText());
                }
            }
        }
        byte[] conteudo = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifesto);
        ByteArrayOutputStream tarFinal = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarFinal, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entrada = new TarArchiveEntry("_coleta/manifesto.json");
            entrada.setSize(conteudo.length);
            entrada.setModTime(Date.from(coleta.concluidaEm == null ? Instant.EPOCH : coleta.concluidaEm));
            tar.putArchiveEntry(entrada);
            tar.write(conteudo);
            tar.closeArchiveEntry();
            tar.finish();
        }
        coleta.resumo = new Coleta.Resumo(nArquivos, nPastas, bytesOriginais, alterados.size(), listaNaoLidos.size());
        coleta.alterados = alterados.stream().limit(1000).toList();
        coleta.naoLidos = listaNaoLidos.stream().limit(1000).toList();
        return Zstd.compress(tarFinal.toByteArray(), 19);
    }

    private void avisar(Coleta coleta) {
        if (coleta.criadaPorId == null) {
            return;
        }
        boolean integra = coleta.situacao == Situacao.INTEGRA;
        try {
            eventos.publishEvent(new AvisoDeModulo(coleta.criadaPorId, "BACKUP:" + coleta.id + ":" + coleta.conferidaEm,
                    integra ? "Backup de " + coleta.cliente + " chegou" : "Backup de " + coleta.cliente + " com diferenças",
                    coleta.mensagem, TELA));
        } catch (RuntimeException e) {
            LOG.warn("Não consegui avisar no sino sobre a coleta {}: {}", coleta.id, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ página de coleta (pública, pelo token)

    private Coleta porToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new ErroDeColeta(404, "LINK_INVALIDO", "Link de coleta inválido.");
        }
        String id = idPorToken.get(token);
        Coleta coleta = id == null ? null : porId.get(id);
        if (coleta == null) {
            throw new ErroDeColeta(404, "LINK_INVALIDO", "Este link não vale mais: a coleta foi cancelada ou apagada no sistema. "
                    + "Peça um link novo a quem está fazendo o backup.");
        }
        return coleta;
    }

    /** Coleta que ainda aceita pedaços (não venceu, não terminou), com o prazo renovado. */
    private Coleta recebendo(String token) {
        Coleta coleta = porToken(token);
        if (coleta.situacao == Situacao.CANCELADA) {
            throw new ErroDeColeta(410, "CANCELADA", "Esta coleta foi cancelada no sistema.");
        }
        if (!coleta.situacao.recebendo()) {
            throw new ErroDeColeta(409, "JA_CONCLUIDA", "Esta coleta já foi concluída.");
        }
        Instant agora = Instant.now();
        if (agora.isAfter(coleta.expiraEm)) {
            throw new ErroDeColeta(410, "VENCIDO", "Este link venceu. Gere outro no sistema (Backups de clientes).");
        }
        coleta.expiraEm = agora.plus(validade());
        coleta.ultimoContato = agora;
        return coleta;
    }

    public Pedidos.Info info(String token, String sessao) {
        Coleta coleta = porToken(token);
        synchronized (trava(coleta.id)) {
            return infoDe(coleta, sessao);
        }
    }

    private Pedidos.Info infoDe(Coleta coleta, String sessao) {
        Cliente cliente = clientes.buscar(coleta.cliente).orElse(new Cliente(coleta.cliente, "", List.of()));
        long tamanhoBloco = (long) configuracao.tamanhoBlocoMib() * 1024 * 1024;
        boolean emOutro = coleta.sessaoHash != null && !coleta.sessaoHash.equals(hashDaSessao(sessao));
        return new Pedidos.Info(coleta.cliente, cliente.pastaSistema(), cliente.ultimaSelecao(),
                coleta.situacao.name(), coleta.mensagem, configuracao.nivelZstd(), tamanhoBloco,
                configuracao.maxParteBytes(), coleta.geracao, coleta.proximoSeq, coleta.cursor, coleta.bytesArquivo,
                coleta.resumo, coleta.conferencia, coleta.nome + ".tar.zst", coleta.raiz, receptorOnline(coleta.id),
                coleta.criadaPor, emOutro && coleta.situacao.recebendo());
    }

    // ------------------------------------------------------------------ sessão: o link preso ao primeiro computador

    /** O segredo de sessão que a página gera (32 bytes aleatórios, base64url); guardado aqui só o SHA-256. */
    private static final Pattern SESSAO = Pattern.compile("[A-Za-z0-9_-]{43}");

    static String hashDaSessao(String sessao) {
        return sessao == null || !SESSAO.matcher(sessao).matches() ? null
                : Envelope.sha256(sessao.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Só o navegador que começou a coleta continua: um link vazado não serve para mandar nada. {@code registrar}: a
     * primeira leitura grava a sessão (antes disso, qualquer um com o link pode começar).
     */
    private void exigirSessao(Coleta coleta, String sessao, boolean registrar) {
        String hash = hashDaSessao(sessao);
        if (hash == null) {
            throw new ErroDeColeta(400, "SEM_SESSAO", "Recarregue a página: ela não se identificou.");
        }
        if (coleta.sessaoHash == null && registrar) {
            coleta.sessaoHash = hash;
            return;
        }
        if (!hash.equals(coleta.sessaoHash)) {
            throw new ErroDeColeta(409, "OUTRA_SESSAO", "Esta coleta já está em andamento em outro computador. "
                    + "Para continuar neste, peça a quem gerou o link para liberar (na tela Backups de clientes).");
        }
    }

    /** Quem gerou o link libera a coleta para continuar em outro computador (a próxima página que abrir o link). */
    public void liberar(String id, UUID usuarioId) {
        Coleta coleta = doReceptor(id, usuarioId);
        synchronized (trava(id)) {
            if (!coleta.situacao.recebendo()) {
                throw new ErroDeColeta(409, "NAO_ESTA_RECEBENDO", "Só dá para liberar uma coleta que ainda está chegando.");
            }
            coleta.sessaoHash = null;
            descartarPendentes(id, new ErroDeColeta(409, "OUTRA_SESSAO", "A coleta foi liberada para outro computador."));
            repositorio.salvar(coleta);
            LOG.info("Coleta {}: liberada para outro computador", id);
        }
    }

    /**
     * As conferências baratas antes de ler o corpo de um pedaço (até dezenas de MB): link, situação, geração e sessão.
     * Sem isso, qualquer um mandaria pedaços enormes com um link inventado e ocuparia a memória do sistema.
     */
    public void prepararEnvio(String token, String geracao, String sessao) {
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            exigirSessao(coleta, sessao, false);
            if (geracao == null || !geracao.equals(coleta.geracao)) {
                throw new ErroDeColeta(409, "GERACAO_ANTIGA",
                        "Esta coleta foi recomeçada em outra janela; feche esta e use a mais nova.");
            }
        }
    }

    public Pedidos.Info iniciarGeracao(String token, Pedidos.Geracao pedido, String sessao) {
        if (pedido.geracao() == null || pedido.geracao().isBlank() || pedido.geracao().length() > 64) {
            throw new ErroDeColeta(400, "GERACAO_INVALIDA", "Geração inválida.");
        }
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            exigirSessao(coleta, sessao, true);
            try {
                if (!pedido.geracao().equals(coleta.geracao)) {
                    descartarPendentes(coleta.id, new ErroDeColeta(409, "GERACAO_ANTIGA",
                            "Esta coleta foi recomeçada em outra janela; feche esta e use a mais nova."));
                    coleta.geracao = pedido.geracao();
                }
                if (pedido.voltarAoInicioDe() != null) {
                    voltarAoInicioDe(coleta, pedido.voltarAoInicioDe());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            coleta.raiz = limitar(pedido.raiz(), 200);
            coleta.pastas = pedido.pastas() == null ? List.of()
                    : pedido.pastas().stream().limit(500).map(p -> limitar(p, 200)).toList();
            coleta.computador = limitar(pedido.computador(), 100);
            coleta.navegador = limitar(pedido.navegador(), 300);
            coleta.totalArquivos = Math.max(0, pedido.totalArquivos());
            coleta.totalBytes = Math.max(0, pedido.totalBytes());
            if (coleta.inicioEnvio == null) {
                coleta.inicioEnvio = Instant.now();
            }
            coleta.situacao = Situacao.RECEBENDO;
            coleta.mensagem = null;
            repositorio.salvar(coleta);
            clientes.buscar(coleta.cliente).ifPresent(c -> clientes.salvar(c.nome(), c.comSelecao(coleta.pastas)));
            trava(coleta.id).notifyAll();
            LOG.info("Coleta {}: leitura {} a partir do pedaço {} ({} arquivos, {} bytes)", coleta.id, coleta.geracao,
                    coleta.proximoSeq, coleta.totalArquivos, coleta.totalBytes);
            return infoDe(coleta, sessao);
        }
    }

    /**
     * Volta para logo antes de {@code caminho}: o último bloco que terminou com a leitura parada no começo desse
     * arquivo (a página sempre fecha um bloco antes de um arquivo grande). Sem esse bloco, volta ao começo. Quem
     * recebe descarta os pedaços dele a partir dali (ele pede pelo número, e o número volta).
     */
    private void voltarAoInicioDe(Coleta coleta, String caminho) throws IOException {
        List<Registro> registros = repositorio.lerRegistros(coleta);
        int manter = 0;
        for (int i = registros.size() - 1; i >= 0; i--) {
            JsonNode fim = registros.get(i).fim();
            if (fim != null && caminho.equals(fim.path("caminho").asText(null)) && fim.path("offset").asLong(-1) == 0) {
                manter = i + 1;
                break;
            }
        }
        List<Registro> mantidos = new ArrayList<>(registros.subList(0, manter));
        repositorio.reescreverRegistros(coleta, mantidos);
        RepositorioDeColetas.aplicarAndamento(coleta, mantidos);
        LOG.info("Coleta {}: voltou para antes de {} (pedaço {})", coleta.id, caminho, coleta.proximoSeq);
    }

    /**
     * Um pedaço da página do cliente: fica na memória até quem recebe gravar (a resposta só sai depois disso, ou ao
     * fim de {@link #ESPERA_GRAVACAO}, para a página mandar de novo).
     */
    public Pedidos.ParteAceita receberParte(String token, long seq, String geracao, String sessao, byte[] corpo) {
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            exigirSessao(coleta, sessao, false);
        }
        if (corpo.length > configuracao.maxParteBytes() + 16L * 1024 * 1024) {
            throw new ErroDeColeta(413, "PEDACO_GRANDE", "Pedaço maior que o permitido.");
        }
        Envelope envelope = Envelope.ler(corpo, json);
        if (envelope.cabecalho().path("seq").asLong(-1) != seq
                || !envelope.cabecalho().path("geracao").asText("").equals(geracao)) {
            throw new ErroDeColeta(400, "PEDACO_INVALIDO", "O cabeçalho do pedaço não confere com o endereço.");
        }
        Pendente pendente;
        synchronized (trava(coleta.id)) {
            if (!geracao.equals(coleta.geracao)) {
                throw new ErroDeColeta(409, "GERACAO_ANTIGA",
                        "Esta coleta foi recomeçada em outra janela; feche esta e use a mais nova.");
            }
            if (seq < coleta.proximoSeq) {
                return new Pedidos.ParteAceita(coleta.proximoSeq, coleta.bytesArquivo);
            }
            if (seq > coleta.proximoSeq + MAX_ADIANTADO) {
                throw new ErroDeColeta(409, "PEDACO_ADIANTADO", "Pedaço muito à frente do esperado.");
            }
            if (!receptorOnline(coleta.id)) {
                throw new ErroDeColeta(503, "RECEPTOR_AUSENTE", "O computador que recebe ("
                        + Objects.requireNonNullElse(coleta.criadaPor, "quem gerou o link")
                        + ") está com a tela Backups de clientes fechada. O envio continua quando ela for aberta.");
            }
            if (coleta.bytesArquivo + envelope.quadro().length > configuracao.limiteColetaBytes()) {
                throw new ErroDeColeta(413, "COLETA_GRANDE", "Esta coleta passou do tamanho máximo permitido ("
                        + configuracao.limiteColetaGb() + " GB). Desmarque pastas ou tipos de arquivo e comece de novo.");
            }
            Repasse r = repasse(coleta.id);
            pendente = r.pendentes.get(seq);
            if (pendente == null) {
                if (r.bytes + envelope.quadro().length > MAX_NA_MEMORIA) {
                    throw new ErroDeColeta(503, "AGUARDE", "Esperando o computador que recebe gravar os anteriores.");
                }
                pendente = new Pendente(seq, envelope.quadro(), envelope.cabecalho(), new CompletableFuture<>());
                r.pendentes.put(seq, pendente);
                r.bytes += envelope.quadro().length;
                trava(coleta.id).notifyAll();
            }
        }
        try {
            pendente.gravado().get(ESPERA_GRAVACAO.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ErroDeColeta(503, "AGUARDE", "O computador que recebe ainda está gravando; mandando de novo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroDeColeta(503, "AGUARDE", "Interrompido; mande de novo.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ErroDeColeta erro) {
                throw erro;
            }
            throw new ErroDeColeta(503, "AGUARDE", "Mande de novo.");
        }
        synchronized (trava(coleta.id)) {
            return new Pedidos.ParteAceita(coleta.proximoSeq, coleta.bytesArquivo);
        }
    }

    private void descartarPendentes(String id, ErroDeColeta motivo) {
        Repasse r = repasses.get(id);
        if (r == null) {
            return;
        }
        for (Pendente p : r.pendentes.values()) {
            p.gravado().completeExceptionally(motivo);
        }
        r.pendentes.clear();
        r.bytes = 0;
        r.parteFinal = null;
    }

    private void amostrar(Coleta coleta) {
        Deque<long[]> fila = amostras.computeIfAbsent(coleta.id, k -> new ArrayDeque<>());
        synchronized (fila) {
            long agora = System.currentTimeMillis();
            fila.addLast(new long[] { agora, coleta.bytesArquivo });
            while (fila.size() > 2 && agora - fila.peekFirst()[0] > JANELA_VELOCIDADE.toMillis()) {
                fila.removeFirst();
            }
        }
    }

    public void progresso(String token, Pedidos.Progresso pedido, String sessao) {
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            exigirSessao(coleta, sessao, false);
            coleta.progresso = new Coleta.Progresso(limitar(pedido.fase(), 40), pedido.lidoBytes(),
                    pedido.lidoArquivos(), pedido.compactadoBytes(), pedido.bufferBytes(),
                    limitar(pedido.arquivoAtual(), 400), Instant.now());
        }
    }

    /** Todos os pedaços gravados por quem recebe: falta ele montar o .tar.zst e conferir. */
    public Pedidos.Info concluir(String token, Pedidos.Conclusao pedido, String sessao) {
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            exigirSessao(coleta, sessao, false);
            if (coleta.geracao == null || !coleta.geracao.equals(pedido.geracao())) {
                throw new ErroDeColeta(409, "GERACAO_ANTIGA", "Esta coleta foi recomeçada em outra janela.");
            }
            if (pedido.totalPartes() != coleta.proximoSeq) {
                throw new ErroDeColeta(409, "FALTAM_PEDACOS", "Ainda faltam pedaços: chegaram " + coleta.proximoSeq
                        + " de " + pedido.totalPartes() + ".");
            }
            coleta.situacao = Situacao.FECHANDO;
            coleta.concluidaEm = Instant.now();
            coleta.mensagem = "Tudo entregue; o computador que recebe está montando e conferindo o arquivo.";
            repositorio.salvar(coleta);
            trava(coleta.id).notifyAll();
            LOG.info("Coleta {}: todos os {} pedaços entregues", coleta.id, coleta.proximoSeq);
            return infoDe(coleta, sessao);
        }
    }

    // ------------------------------------------------------------------ limpeza

    /** Tira do histórico as coletas terminadas há mais de {@code dias} e links nunca usados vencidos há uma semana. */
    public int limpar(int dias) {
        Instant agora = Instant.now();
        int apagadas = 0;
        for (Coleta c : List.copyOf(listar())) {
            boolean velha;
            if (c.situacao.terminada()) {
                velha = dias > 0 && c.conferidaEm != null && c.conferidaEm.isBefore(agora.minus(Duration.ofDays(dias)));
            } else if (c.situacao == Situacao.AGUARDANDO || c.situacao == Situacao.CANCELADA) {
                velha = c.expiraEm.isBefore(agora.minus(Duration.ofDays(7)));
            } else {
                velha = false;
            }
            if (velha) {
                try {
                    if (c.situacao == Situacao.AGUARDANDO) {
                        c.situacao = Situacao.CANCELADA;
                    }
                    apagar(c.id);
                    apagadas++;
                } catch (ErroDeColeta e) {
                    LOG.info("Limpeza pulou a coleta {}: {}", c.id, e.getMessage());
                }
            }
        }
        return apagadas;
    }

    // ------------------------------------------------------------------ apoio

    private Duration validade() {
        return Duration.ofHours(Math.max(1, configuracao.validadeLinkHoras()));
    }

    private byte[] bytesAleatorios(int n) {
        byte[] bytes = new byte[n];
        aleatorio.nextBytes(bytes);
        return bytes;
    }

    private static String limitar(String texto, int maximo) {
        if (texto == null) {
            return null;
        }
        return texto.length() > maximo ? texto.substring(0, maximo) : texto;
    }

    private static List<String> limitar(List<String> lista) {
        return lista == null ? List.of() : lista.stream().limit(200).map(s -> limitar(s, 500)).toList();
    }
}
