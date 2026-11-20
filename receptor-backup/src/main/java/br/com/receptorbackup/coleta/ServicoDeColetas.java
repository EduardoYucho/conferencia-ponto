package br.com.receptorbackup.coleta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.luben.zstd.Zstd;

import br.com.receptorbackup.cliente.Cliente;
import br.com.receptorbackup.cliente.RepositorioDeClientes;
import br.com.receptorbackup.coleta.Coleta.Situacao;
import br.com.receptorbackup.config.Propriedades;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * O recebimento de uma coleta.
 *
 * <p>Cada pedaço é um quadro zstd independente; o backup é a concatenação dos quadros na ordem ({@code seq}), o que
 * já é um .zst válido. Pedaço fora de ordem espera em {@code pendentes}; quando o da vez chega, ele e os seguintes
 * entram no arquivo e cada um ganha uma linha em blocos.jsonl. No fim, o Receptor acrescenta o último quadro: o
 * manifesto (SHA-256 de cada arquivo) e o fim do .tar.
 */
@Service
public class ServicoDeColetas {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeColetas.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long MAX_ADIANTADO = 64;
    private static final DateTimeFormatter NOME_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final Duration JANELA_VELOCIDADE = Duration.ofSeconds(60);

    private final Propriedades propriedades;
    private final RepositorioDeColetas repositorio;
    private final RepositorioDeClientes clientes;
    private final ObjectMapper json;
    private final SecureRandom aleatorio = new SecureRandom();

    private final Map<String, Coleta> porId = new ConcurrentHashMap<>();
    private final Map<String, String> idPorToken = new ConcurrentHashMap<>();
    private final Map<String, Object> travas = new ConcurrentHashMap<>();
    private final Map<String, Deque<long[]>> amostras = new ConcurrentHashMap<>();
    /** Bytes já processados na conferência/extração em andamento, por coleta. */
    private final Map<String, Long> andamentoDoTrabalho = new ConcurrentHashMap<>();
    private final ExecutorService trabalhos = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "receptor-conferencia");
        t.setDaemon(true);
        return t;
    });

    public ServicoDeColetas(Propriedades propriedades, RepositorioDeColetas repositorio,
            RepositorioDeClientes clientes, ObjectMapper json) {
        this.propriedades = propriedades;
        this.repositorio = repositorio;
        this.clientes = clientes;
        this.json = json;
    }

    @PostConstruct
    void carregar() {
        for (Coleta coleta : repositorio.carregarTodas()) {
            porId.put(coleta.id, coleta);
            idPorToken.put(coleta.token, coleta.id);
            if (coleta.situacao == Situacao.MONTANDO) {
                coleta.situacao = Situacao.RECEBENDO;
                coleta.mensagem = "O Receptor parou enquanto fechava o arquivo; a página pode concluir de novo.";
                repositorio.salvar(coleta);
            } else if (coleta.situacao == Situacao.CONFERINDO) {
                agendarConferencia(coleta);
            }
            if (coleta.extracao != null && "EXTRAINDO".equals(coleta.extracao.situacao())) {
                coleta.extracao = new Coleta.Extracao("FALHOU", coleta.extracao.destino(), coleta.extracao.bytes(),
                        coleta.extracao.total(), coleta.extracao.inicio(), Instant.now(), List.of(),
                        "Interrompida: o Receptor foi fechado durante a extração.");
                repositorio.salvar(coleta);
            }
        }
        LOG.info("Receptor com {} coleta(s) em {}", porId.size(), propriedades.pastaDados());
    }

    @PreDestroy
    void parar() {
        trabalhos.shutdownNow();
    }

    private Object trava(String id) {
        return travas.computeIfAbsent(id, k -> new Object());
    }

    // ------------------------------------------------------------------ tela do Receptor

    public Coleta criar(String nomeCliente) {
        Cliente cliente = clientes.buscar(nomeCliente)
                .orElseThrow(() -> new ErroDeColeta(404, "CLIENTE_DESCONHECIDO", "Cliente não cadastrado: " + nomeCliente));
        Instant agora = Instant.now();
        Coleta coleta = new Coleta();
        String data = LocalDateTime.ofInstant(agora, ZoneId.systemDefault()).format(NOME_DATA);
        coleta.id = data.replace("_", "-") + "-" + HexFormat.of().formatHex(bytesAleatorios(2));
        coleta.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytesAleatorios(32));
        coleta.cliente = cliente.nome();
        coleta.pastaCliente = NomesSeguros.pastaDoCliente(cliente.nome());
        coleta.nome = coleta.pastaCliente + "_" + data;
        coleta.criadaEm = agora;
        coleta.expiraEm = agora.plus(validade());
        coleta.situacao = Situacao.AGUARDANDO;
        repositorio.salvar(coleta);
        porId.put(coleta.id, coleta);
        idPorToken.put(coleta.token, coleta.id);
        LOG.info("Coleta {} criada para {}", coleta.id, coleta.cliente);
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

    public Path arquivoFinal(Coleta coleta) {
        return repositorio.arquivoFinal(coleta);
    }

    public Path pastaDoCliente(Coleta coleta) {
        return repositorio.pastaDoCliente(coleta);
    }

    /** Velocidade de chegada (bytes/s) no último minuto. */
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

    public Optional<Long> andamentoDoTrabalho(String id) {
        return Optional.ofNullable(andamentoDoTrabalho.get(id));
    }

    public void cancelar(String id) {
        Coleta coleta = buscar(id);
        synchronized (trava(id)) {
            if (!coleta.situacao.recebendo() && coleta.situacao != Situacao.MONTANDO) {
                throw new ErroDeColeta(409, "NAO_CANCELAVEL", "Só dá para cancelar uma coleta que ainda está chegando.");
            }
            coleta.situacao = Situacao.CANCELADA;
            coleta.mensagem = "Cancelada no Receptor.";
            try {
                Files.deleteIfExists(repositorio.arquivoParcial(coleta));
            } catch (IOException e) {
                LOG.warn("Não consegui apagar o parcial da coleta {}: {}", id, e.getMessage());
            }
            repositorio.limparTrabalho(coleta);
            repositorio.salvar(coleta);
        }
    }

    public void apagar(String id) {
        Coleta coleta = buscar(id);
        synchronized (trava(id)) {
            if (coleta.situacao == Situacao.CONFERINDO || coleta.situacao == Situacao.MONTANDO
                    || (coleta.extracao != null && "EXTRAINDO".equals(coleta.extracao.situacao()))) {
                throw new ErroDeColeta(409, "EM_USO", "Espere a conferência ou a extração terminar.");
            }
            try {
                repositorio.apagarColeta(coleta);
            } catch (IOException e) {
                throw new ErroDeColeta(500, "FALHA_AO_APAGAR", "Não consegui apagar: " + e.getMessage());
            }
            porId.remove(id);
            idPorToken.remove(coleta.token);
            amostras.remove(id);
            LOG.info("Coleta {} apagada", id);
        }
    }

    public void conferirDeNovo(String id) {
        Coleta coleta = buscar(id);
        synchronized (trava(id)) {
            if (coleta.situacao != Situacao.INTEGRA && coleta.situacao != Situacao.DIVERGENTE) {
                throw new ErroDeColeta(409, "SEM_ARQUIVO", "Só dá para conferir um backup que já chegou inteiro.");
            }
            coleta.situacao = Situacao.CONFERINDO;
            repositorio.salvar(coleta);
        }
        agendarConferencia(coleta);
    }

    public void extrair(String id, String destinoEscolhido) {
        Coleta coleta = buscar(id);
        Path destino;
        synchronized (trava(id)) {
            if (coleta.situacao != Situacao.INTEGRA && coleta.situacao != Situacao.DIVERGENTE) {
                throw new ErroDeColeta(409, "SEM_ARQUIVO", "O backup ainda não chegou inteiro.");
            }
            if (coleta.extracao != null && "EXTRAINDO".equals(coleta.extracao.situacao())) {
                throw new ErroDeColeta(409, "JA_EXTRAINDO", "Já está extraindo.");
            }
            if (destinoEscolhido == null || destinoEscolhido.isBlank()) {
                destino = repositorio.pastaDoCliente(coleta).resolve("extraido").resolve(coleta.nome);
            } else {
                destino = Path.of(destinoEscolhido.trim());
                if (!destino.isAbsolute()) {
                    throw new ErroDeColeta(400, "DESTINO_INVALIDO", "Informe o caminho completo da pasta (ex.: D:\\Testes\\Cliente).");
                }
            }
            coleta.extracao = new Coleta.Extracao("EXTRAINDO", destino.toString(), 0, coleta.resumo == null ? 0
                    : coleta.resumo.bytesOriginais(), Instant.now(), null, List.of(), null);
            repositorio.salvar(coleta);
        }
        Path destinoFinal = destino;
        trabalhos.submit(() -> {
            Instant inicio = coleta.extracao.inicio();
            long total = coleta.extracao.total();
            try {
                LeitorDoBackup.ResultadoExtracao resultado = LeitorDoBackup.extrair(repositorio.arquivoFinal(coleta),
                        destinoFinal, bytes -> andamentoDoTrabalho.put(coleta.id, bytes));
                String mensagem = resultado.arquivos() + " arquivo(s) extraído(s)"
                        + (resultado.ignorados().isEmpty() ? "." : "; " + resultado.ignorados().size()
                                + " ignorado(s) por terem nome que o Windows não aceita.");
                coleta.extracao = new Coleta.Extracao("EXTRAIDO", destinoFinal.toString(), resultado.bytes(), total,
                        inicio, Instant.now(), resultado.ignorados(), mensagem);
            } catch (ErroDeColeta e) {
                coleta.extracao = new Coleta.Extracao("FALHOU", destinoFinal.toString(), 0, total, inicio,
                        Instant.now(), List.of(), e.getMessage());
            } catch (Exception e) {
                LOG.warn("Extração da coleta {} falhou", coleta.id, e);
                coleta.extracao = new Coleta.Extracao("FALHOU", destinoFinal.toString(), 0, total, inicio,
                        Instant.now(), List.of(), "Falhou: " + e.getMessage());
            } finally {
                andamentoDoTrabalho.remove(coleta.id);
                synchronized (trava(coleta.id)) {
                    repositorio.salvar(coleta);
                }
            }
        });
    }

    // ------------------------------------------------------------------ página de coleta (pública, pelo token)

    private Coleta porToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new ErroDeColeta(404, "LINK_INVALIDO", "Link de coleta inválido.");
        }
        String id = idPorToken.get(token);
        Coleta coleta = id == null ? null : porId.get(id);
        if (coleta == null) {
            throw new ErroDeColeta(404, "LINK_INVALIDO", "Link de coleta inválido ou apagado.");
        }
        return coleta;
    }

    /** Coleta que ainda aceita pedaços (não venceu, não terminou), com o prazo renovado. */
    private Coleta recebendo(String token) {
        Coleta coleta = porToken(token);
        if (coleta.situacao == Situacao.CANCELADA) {
            throw new ErroDeColeta(410, "CANCELADA", "Esta coleta foi cancelada no Receptor.");
        }
        if (!coleta.situacao.recebendo()) {
            throw new ErroDeColeta(409, "JA_CONCLUIDA", "Esta coleta já foi concluída.");
        }
        Instant agora = Instant.now();
        if (agora.isAfter(coleta.expiraEm)) {
            throw new ErroDeColeta(410, "VENCIDO", "Este link venceu. Gere outro no Receptor.");
        }
        coleta.expiraEm = agora.plus(validade());
        coleta.ultimoContato = agora;
        return coleta;
    }

    public Pedidos.Info info(String token) {
        Coleta coleta = porToken(token);
        synchronized (trava(coleta.id)) {
            return infoDe(coleta);
        }
    }

    private Pedidos.Info infoDe(Coleta coleta) {
        Cliente cliente = clientes.buscar(coleta.cliente).orElse(new Cliente(coleta.cliente, "", List.of()));
        long tamanhoBloco = (long) propriedades.tamanhoBlocoMib() * 1024 * 1024;
        return new Pedidos.Info(coleta.cliente, cliente.pastaSistema(), cliente.ultimaSelecao(),
                coleta.situacao.name(), coleta.mensagem, propriedades.nivelZstd(), tamanhoBloco,
                propriedades.maxParteBytes(), coleta.geracao, coleta.proximoSeq, coleta.cursor, coleta.bytesArquivo,
                coleta.resumo, coleta.conferencia, coleta.nome + ".tar.zst", coleta.raiz);
    }

    public Pedidos.Info iniciarGeracao(String token, Pedidos.Geracao pedido) {
        if (pedido.geracao() == null || pedido.geracao().isBlank() || pedido.geracao().length() > 64) {
            throw new ErroDeColeta(400, "GERACAO_INVALIDA", "Geração inválida.");
        }
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            try {
                if (!pedido.geracao().equals(coleta.geracao)) {
                    RepositorioDeColetas.apagarArvore(repositorio.pendentes(coleta));
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
            LOG.info("Coleta {}: leitura {} a partir do pedaço {} ({} arquivos, {} bytes)", coleta.id, coleta.geracao,
                    coleta.proximoSeq, coleta.totalArquivos, coleta.totalBytes);
            return infoDe(coleta);
        }
    }

    /**
     * Volta o backup para logo antes de {@code caminho}: o último bloco que terminou com a leitura parada no começo
     * desse arquivo (a página sempre fecha um bloco antes de um arquivo grande). Sem esse bloco, volta ao começo.
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
        Path parcial = repositorio.arquivoParcial(coleta);
        if (Files.exists(parcial)) {
            try (FileChannel canal = FileChannel.open(parcial, StandardOpenOption.WRITE)) {
                canal.truncate(coleta.bytesArquivo);
            }
        }
        RepositorioDeColetas.apagarArvore(repositorio.pendentes(coleta));
        LOG.info("Coleta {}: voltou para antes de {} (pedaço {})", coleta.id, caminho, coleta.proximoSeq);
    }

    public Pedidos.ParteAceita receberParte(String token, long seq, String geracao, byte[] corpo) {
        Coleta coleta = recebendo(token);
        if (corpo.length > propriedades.maxParteBytes() + 16L * 1024 * 1024) {
            throw new ErroDeColeta(413, "PEDACO_GRANDE", "Pedaço maior que o permitido.");
        }
        Envelope envelope = Envelope.ler(corpo, json);
        if (envelope.cabecalho().path("seq").asLong(-1) != seq
                || !envelope.cabecalho().path("geracao").asText("").equals(geracao)) {
            throw new ErroDeColeta(400, "PEDACO_INVALIDO", "O cabeçalho do pedaço não confere com o endereço.");
        }
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
            verificarEspaco(corpo.length);
            try {
                Path pendentes = repositorio.pendentes(coleta);
                Files.createDirectories(pendentes);
                Path temporario = pendentes.resolve(seq + ".parte.tmp");
                Files.write(temporario, corpo);
                Files.move(temporario, pendentes.resolve(seq + ".parte"), StandardCopyOption.REPLACE_EXISTING);
                aplicarPendentes(coleta);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            repositorio.salvar(coleta);
            return new Pedidos.ParteAceita(coleta.proximoSeq, coleta.bytesArquivo);
        }
    }

    private void aplicarPendentes(Coleta coleta) throws IOException {
        Path pendentes = repositorio.pendentes(coleta);
        Path parcial = repositorio.arquivoParcial(coleta);
        Files.createDirectories(parcial.getParent());
        while (true) {
            Path proximo = pendentes.resolve(coleta.proximoSeq + ".parte");
            if (!Files.exists(proximo)) {
                return;
            }
            Envelope envelope = Envelope.ler(Files.readAllBytes(proximo), json);
            try (FileChannel canal = FileChannel.open(parcial, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                canal.truncate(coleta.bytesArquivo);
                canal.position(coleta.bytesArquivo);
                ByteBuffer dados = ByteBuffer.wrap(envelope.quadro());
                while (dados.hasRemaining()) {
                    canal.write(dados);
                }
                canal.force(false);
            }
            JsonNode cab = envelope.cabecalho();
            Registro registro = new Registro(coleta.proximoSeq, coleta.bytesArquivo + envelope.quadro().length,
                    cab.path("bruto").asLong(0), cab.get("fim"), cab.get("arquivos"), cab.get("naoLidos"));
            repositorio.anexarRegistro(coleta, registro);
            coleta.proximoSeq++;
            coleta.bytesArquivo = registro.offsetFinal();
            coleta.cursor = registro.fim();
            coleta.brutoRecebido += registro.bruto();
            coleta.arquivosRecebidos += registro.arquivos() == null ? 0 : registro.arquivos().size();
            Files.deleteIfExists(proximo);
            amostrar(coleta);
        }
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

    private void verificarEspaco(long chegando) {
        try {
            Path pasta = propriedades.pastaDados();
            Files.createDirectories(pasta);
            long livre = Files.getFileStore(pasta).getUsableSpace();
            if (livre - chegando < propriedades.espacoMinimoLivreBytes()) {
                throw new ErroDeColeta(507, "SEM_ESPACO",
                        "A máquina que recebe está com pouco espaço livre. Libere espaço lá e o envio continua.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void progresso(String token, Pedidos.Progresso pedido) {
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            coleta.progresso = new Coleta.Progresso(limitar(pedido.fase(), 40), pedido.lidoBytes(),
                    pedido.lidoArquivos(), pedido.compactadoBytes(), pedido.bufferBytes(),
                    limitar(pedido.arquivoAtual(), 400), Instant.now());
        }
    }

    public Pedidos.Info concluir(String token, Pedidos.Conclusao pedido) {
        Coleta coleta = recebendo(token);
        synchronized (trava(coleta.id)) {
            if (coleta.geracao == null || !coleta.geracao.equals(pedido.geracao())) {
                throw new ErroDeColeta(409, "GERACAO_ANTIGA", "Esta coleta foi recomeçada em outra janela.");
            }
            if (pedido.totalPartes() != coleta.proximoSeq) {
                throw new ErroDeColeta(409, "FALTAM_PEDACOS", "Ainda faltam pedaços: chegaram " + coleta.proximoSeq
                        + " de " + pedido.totalPartes() + ".");
            }
            coleta.situacao = Situacao.MONTANDO;
            try {
                fecharArquivo(coleta);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Coleta {}: falhou ao fechar o arquivo", coleta.id, e);
                coleta.situacao = Situacao.RECEBENDO;
                coleta.mensagem = "Falhou ao fechar o arquivo: " + e.getMessage();
                repositorio.salvar(coleta);
                throw new ErroDeColeta(500, "FALHA_AO_FECHAR", "O Receptor não conseguiu fechar o arquivo. Tente concluir de novo.");
            }
            coleta.situacao = Situacao.CONFERINDO;
            repositorio.salvar(coleta);
            agendarConferencia(coleta);
            return infoDe(coleta);
        }
    }

    /** Acrescenta o manifesto e o fim do .tar, troca .parcial por .tar.zst e grava o resumo ao lado. */
    private void fecharArquivo(Coleta coleta) throws IOException {
        List<Registro> registros = repositorio.lerRegistros(coleta);
        Instant agora = Instant.now();
        ObjectNode manifesto = json.createObjectNode();
        manifesto.put("formato", "receptor-backup/1");
        manifesto.put("coleta", coleta.id);
        manifesto.put("cliente", coleta.cliente);
        manifesto.put("raiz", coleta.raiz);
        manifesto.set("pastas", json.valueToTree(coleta.pastas));
        manifesto.put("computador", coleta.computador);
        manifesto.put("navegador", coleta.navegador);
        manifesto.put("inicio", String.valueOf(coleta.inicioEnvio));
        manifesto.put("fim", agora.toString());
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
        byte[] conteudoManifesto = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifesto);
        ByteArrayOutputStream tarFinal = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarFinal, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entrada = new TarArchiveEntry(LeitorDoBackup.MANIFESTO);
            entrada.setSize(conteudoManifesto.length);
            entrada.setModTime(Date.from(agora));
            tar.putArchiveEntry(entrada);
            tar.write(conteudoManifesto);
            tar.closeArchiveEntry();
            tar.finish();
        }
        byte[] quadroFinal = Zstd.compress(tarFinal.toByteArray(), 19);

        Path parcial = repositorio.arquivoParcial(coleta);
        Files.createDirectories(parcial.getParent());
        try (FileChannel canal = FileChannel.open(parcial, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            canal.truncate(coleta.bytesArquivo);
            canal.position(coleta.bytesArquivo);
            ByteBuffer dados = ByteBuffer.wrap(quadroFinal);
            while (dados.hasRemaining()) {
                canal.write(dados);
            }
            canal.force(true);
        }
        Path finalizado = repositorio.arquivoFinal(coleta);
        Files.move(parcial, finalizado, StandardCopyOption.REPLACE_EXISTING);
        coleta.tamanhoArquivo = Files.size(finalizado);
        coleta.concluidaEm = agora;
        coleta.resumo = new Coleta.Resumo(nArquivos, nPastas, bytesOriginais, alterados.size(), listaNaoLidos.size());
        coleta.alterados = alterados.stream().limit(1000).toList();
        coleta.naoLidos = listaNaoLidos.stream().limit(1000).toList();
        gravarResumo(coleta);
        repositorio.limparTrabalho(coleta);
        LOG.info("Coleta {}: arquivo fechado ({} arquivos, {} bytes originais, {} bytes no disco)", coleta.id,
                nArquivos, bytesOriginais, coleta.tamanhoArquivo);
    }

    private void gravarResumo(Coleta coleta) {
        ObjectNode resumo = json.createObjectNode();
        resumo.put("cliente", coleta.cliente);
        resumo.put("arquivo", coleta.nome + ".tar.zst");
        resumo.put("raiz", coleta.raiz);
        resumo.set("pastas", json.valueToTree(coleta.pastas));
        resumo.put("computador", coleta.computador);
        resumo.put("inicio", String.valueOf(coleta.inicioEnvio));
        resumo.put("fim", String.valueOf(coleta.concluidaEm));
        resumo.set("resumo", json.valueToTree(coleta.resumo));
        resumo.set("conferencia", json.valueToTree(coleta.conferencia));
        resumo.set("alteradosDuranteACopia", json.valueToTree(coleta.alterados));
        resumo.set("naoLidos", json.valueToTree(coleta.naoLidos));
        ArquivosJson.gravar(json, repositorio.arquivoResumo(coleta), resumo);
    }

    private void agendarConferencia(Coleta coleta) {
        trabalhos.submit(() -> {
            Instant inicio = Instant.now();
            Coleta.Conferencia resultado;
            try {
                LeitorDoBackup.ResultadoConferencia r = LeitorDoBackup.conferir(repositorio.arquivoFinal(coleta), json,
                        bytes -> andamentoDoTrabalho.put(coleta.id, bytes));
                resultado = new Coleta.Conferencia(r.integra() ? "INTEGRA" : "DIVERGENTE", inicio, Instant.now(),
                        r.arquivosConferidos(), r.bytesConferidos(), r.divergentes(), r.faltando(), r.sobrando(),
                        r.mensagem());
            } catch (Exception e) {
                LOG.warn("Conferência da coleta {} falhou", coleta.id, e);
                resultado = new Coleta.Conferencia("DIVERGENTE", inicio, Instant.now(), 0, 0, List.of(), List.of(),
                        List.of(), "Não consegui ler o backup: " + e.getMessage());
            } finally {
                andamentoDoTrabalho.remove(coleta.id);
            }
            synchronized (trava(coleta.id)) {
                coleta.conferencia = resultado;
                coleta.situacao = "INTEGRA".equals(resultado.situacao()) ? Situacao.INTEGRA : Situacao.DIVERGENTE;
                coleta.mensagem = resultado.mensagem();
                repositorio.salvar(coleta);
                gravarResumo(coleta);
            }
            LOG.info("Coleta {}: {}", coleta.id, resultado.mensagem());
        });
    }

    // ------------------------------------------------------------------ limpeza

    /** Apaga as coletas terminadas há mais de {@code dias} (e links nunca usados vencidos há uma semana). */
    public int limpar(int dias, boolean manterUltimaPorCliente) {
        Instant agora = Instant.now();
        Map<String, String> ultimaPorCliente = new ConcurrentHashMap<>();
        if (manterUltimaPorCliente) {
            for (Coleta c : listar()) {
                if (c.situacao.temArquivo()) {
                    ultimaPorCliente.putIfAbsent(c.pastaCliente, c.id);
                }
            }
        }
        int apagadas = 0;
        for (Coleta c : List.copyOf(listar())) {
            boolean velha;
            if (c.situacao.temArquivo() || c.situacao == Situacao.FALHOU) {
                velha = dias > 0 && c.concluidaEm != null && c.concluidaEm.isBefore(agora.minus(Duration.ofDays(dias)))
                        && !c.id.equals(ultimaPorCliente.get(c.pastaCliente)) && c.situacao != Situacao.CONFERINDO;
            } else if (c.situacao == Situacao.AGUARDANDO || c.situacao == Situacao.CANCELADA) {
                velha = c.expiraEm.isBefore(agora.minus(Duration.ofDays(7)));
            } else {
                velha = false;
            }
            if (velha) {
                try {
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
        return Duration.ofHours(Math.max(1, propriedades.validadeLinkHoras()));
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
}
