package br.com.expedio.modulos.backup.infrastructure.coleta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

import br.com.expedio.application.evento.AvisoDeModulo;
import br.com.expedio.modulos.backup.domain.Cliente;
import br.com.expedio.modulos.backup.domain.Coleta;
import br.com.expedio.modulos.backup.domain.ErroDeColeta;
import br.com.expedio.modulos.backup.domain.Pedidos;
import br.com.expedio.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;

/**
 * O repasse sem a página nem o navegador: os pedaços são montados aqui como a página monta (tar em blocos, cada bloco
 * um quadro zstd, com o cursor e os SHA-256 no cabeçalho) e uma thread faz o papel do navegador de quem recebe. O
 * teste de ponta a ponta com a página e o montador de verdade está em src/test/js/backup-ponta-a-ponta.mjs.
 */
class ServicoDeColetasTest {

    @TempDir
    Path pasta;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID quem = UUID.randomUUID();
    /** O segredo de sessão da página (43 caracteres base64url). */
    private static final String SESSAO = "S".repeat(43);
    private final List<Object> avisos = new CopyOnWriteArrayList<>();
    private final ExecutorService paginas = Executors.newCachedThreadPool();
    private ServicoDeColetas servico;
    private RepositorioDeClientes clientes;
    private ConfiguracaoDoBackup configuracao;

    @BeforeEach
    void preparar() {
        configuracao = new ConfiguracaoDoBackup(pasta.resolve("estado"), 0, "", "", false, 48, 3, 1, 32, 90, 1);
        clientes = new RepositorioDeClientes(configuracao, json);
        clientes.salvar(null, new Cliente("Loja Teste", "G:\\sislojas3", List.of()));
        servico = novoServico();
    }

    @AfterEach
    void encerrar() {
        paginas.shutdownNow();
    }

    private ServicoDeColetas novoServico() {
        ServicoDeColetas s = new ServicoDeColetas(configuracao, new RepositorioDeColetas(configuracao, json), clientes,
                json, avisos::add);
        s.carregar();
        return s;
    }

    /** Três arquivos, um por bloco; o cursor de cada bloco aponta o arquivo seguinte. */
    private record Arquivo(String caminho, byte[] conteudo) {
    }

    private final List<Arquivo> arquivos = List.of(
            new Arquivo("sislojas3/AppSrc/Tela.src", "Object oTela is a dbView\n".repeat(500).getBytes(StandardCharsets.UTF_8)),
            new Arquivo("sislojas3/Data/CLIENTES.DAT", " ".repeat(300_000).getBytes(StandardCharsets.US_ASCII)),
            new Arquivo("sislojas3/Data/CLIENTES.K1", HexFormat.of().parseHex("00ff10ab".repeat(1000))));

    private byte[] envelope(int seq, String geracao) throws IOException {
        Arquivo arquivo = arquivos.get(seq);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        try (TarArchiveOutputStream saida = new TarArchiveOutputStream(tar, StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entrada = new TarArchiveEntry(arquivo.caminho());
            entrada.setSize(arquivo.conteudo().length);
            saida.putArchiveEntry(entrada);
            saida.write(arquivo.conteudo());
            saida.closeArchiveEntry();
            saida.finish();
        }
        // só a entrada (cabeçalho, dados e preenchimento): o fim do .tar vem no último pedaço
        long usado = 512 + ((arquivo.conteudo().length + 511) / 512) * 512L;
        byte[] bloco = Arrays.copyOf(tar.toByteArray(), (int) usado);
        byte[] quadro = Zstd.compress(bloco, 3);
        ObjectNode cab = json.createObjectNode();
        cab.put("seq", seq);
        cab.put("geracao", geracao);
        cab.put("cifrado", true);
        cab.put("shaQuadro", Envelope.sha256(quadro));
        cab.put("bruto", bloco.length);
        ObjectNode fim = cab.putObject("fim");
        if (seq + 1 < arquivos.size()) {
            fim.put("caminho", arquivos.get(seq + 1).caminho());
            fim.put("offset", 0);
        } else {
            fim.put("fimDaLista", true);
        }
        ArrayNode lista = cab.putArray("arquivos");
        ObjectNode item = lista.addObject();
        item.put("caminho", arquivo.caminho());
        item.put("tipo", "arquivo");
        item.put("tamanho", arquivo.conteudo().length);
        item.put("sha256", sha(arquivo.conteudo()));
        cab.putArray("naoLidos");
        return Envelope.montar(cab, quadro, json);
    }

    private static String sha(byte[] dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Pedidos.Geracao geracao(String id, String voltar) {
        return new Pedidos.Geracao(id, "sislojas3", List.of("AppSrc", "Data"), "SERVIDOR", "teste", 3, 304_000, voltar);
    }

    /** A página do cliente manda o pedaço (a resposta só sai quando quem recebe gravar). */
    private Future<Pedidos.ParteAceita> enviar(String token, int seq, String geracao) throws IOException {
        byte[] corpo = envelope(seq, geracao);
        return paginas.submit(() -> servico.receberParte(token, seq, geracao, SESSAO, corpo));
    }

    /** O navegador de quem recebe: pega e confirma {@code quantos} pedaços, guardando os quadros na ordem. */
    private List<byte[]> receber(String id, int quantos) {
        List<byte[]> gravados = new ArrayList<>();
        while (gravados.size() < quantos) {
            Optional<Pedidos.Entrega> entrega = servico.proximaParte(id, quem, Duration.ofSeconds(5));
            assertThat(entrega).as("pedaço %d chegou para quem recebe", gravados.size()).isPresent();
            gravados.add(entrega.get().quadro());
            servico.confirmarParte(id, quem, entrega.get().seq(), sha(entrega.get().quadro()));
        }
        return gravados;
    }

    /** O .tar.zst que quem recebe monta: os quadros na ordem e o último pedaço; devolve caminho → conteúdo. */
    private Map<String, byte[]> abrir(List<byte[]> quadros, byte[] parteFinal) throws IOException {
        ByteArrayOutputStream arquivo = new ByteArrayOutputStream();
        for (byte[] q : quadros) {
            arquivo.write(q);
        }
        arquivo.write(parteFinal);
        Map<String, byte[]> conteudo = new HashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ZstdInputStream(new ByteArrayInputStream(arquivo.toByteArray())), StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entrada;
            while ((entrada = tar.getNextEntry()) != null) {
                conteudo.put(entrada.getName(), tar.readAllBytes());
            }
        }
        return conteudo;
    }

    @Test
    void repasseForaDeOrdemSemGravarNadaDoBackupEConferenciaNoNavegador() throws Exception {
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);

        // a página manda 1 e 2 antes do 0: tudo espera quem recebe, que grava na ordem
        Future<Pedidos.ParteAceita> p1 = enviar(coleta.token, 1, "g1");
        Future<Pedidos.ParteAceita> p2 = enviar(coleta.token, 2, "g1");
        Thread.sleep(200);
        assertThat(p1).isNotDone();
        Future<Pedidos.ParteAceita> p0 = enviar(coleta.token, 0, "g1");
        List<byte[]> quadros = receber(coleta.id, 3);
        assertThat(p0.get(5, TimeUnit.SECONDS).proximoSeq()).isGreaterThanOrEqualTo(1);
        assertThat(p2.get(5, TimeUnit.SECONDS).proximoSeq()).isEqualTo(3);
        // repetido (a resposta anterior se perdeu): aceito na hora, sem duplicar
        assertThat(servico.receberParte(coleta.token, 1, "g1", SESSAO, envelope(1, "g1")).proximoSeq()).isEqualTo(3);

        // nada do backup no disco do sistema: só os dados de controle
        try (var todos = Files.walk(pasta.resolve("estado"))) {
            assertThat(todos.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .allMatch(n -> n.equals("coleta.json") || n.equals("blocos.jsonl") || n.equals("clientes.json"));
        }

        assertThatThrownBy(() -> servico.concluir(coleta.token, new Pedidos.Conclusao("g1", 4), SESSAO))
                .isInstanceOf(ErroDeColeta.class).hasMessageContaining("faltam");
        servico.concluir(coleta.token, new Pedidos.Conclusao("g1", 3), SESSAO);
        assertThat(servico.buscar(coleta.id).situacao).isEqualTo(Coleta.Situacao.FECHANDO);

        byte[] parteFinal = servico.parteFinal(coleta.id, quem);
        assertThat(servico.parteFinal(coleta.id, quem)).as("o último pedaço é sempre o mesmo").isEqualTo(parteFinal);
        Map<String, byte[]> conteudo = abrir(quadros, parteFinal);
        for (Arquivo a : arquivos) {
            assertThat(conteudo.get(a.caminho())).isEqualTo(a.conteudo());
        }
        JsonNode manifesto = json.readTree(conteudo.get("_coleta/manifesto.json"));
        assertThat(manifesto.path("arquivos")).hasSize(3);
        assertThat(manifesto.path("arquivos").get(1).path("sha256").asText()).isEqualTo(sha(arquivos.get(1).conteudo()));

        Coleta conferida = servico.registrarResultado(coleta.id, quem, new Pedidos.Resultado(true, 3, 304_000,
                1234, List.of(), List.of(), List.of(), "Todos os 3 arquivos idênticos aos lidos no cliente."));
        assertThat(conferida.situacao).isEqualTo(Coleta.Situacao.INTEGRA);
        assertThat(conferida.resumo.arquivos()).isEqualTo(3);
        assertThat(conferida.tamanhoArquivo).isEqualTo(1234);
        assertThat(avisos).singleElement().isInstanceOfSatisfying(AvisoDeModulo.class, aviso -> {
            assertThat(aviso.usuarioId()).isEqualTo(quem);
            assertThat(aviso.titulo()).isEqualTo("Backup de Loja Teste chegou");
            assertThat(aviso.link()).isEqualTo("/backups-clientes");
        });
    }

    @Test
    void semQuemRecebeOPedacoEsperaNoCliente() throws Exception {
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);
        servico.esquecerReceptor(coleta.id);
        assertThat(servico.info(coleta.token, SESSAO).receptorOnline()).isFalse();
        assertThatThrownBy(() -> servico.receberParte(coleta.token, 0, "g1", SESSAO, envelope(0, "g1")))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.codigo()).isEqualTo("RECEPTOR_AUSENTE");
                });
        // a tela abriu: quem recebe pede e o envio segue
        assertThat(servico.proximaParte(coleta.id, quem, Duration.ofMillis(50))).isEmpty();
        assertThat(servico.receptorOnline(coleta.id)).isTrue();
        Future<Pedidos.ParteAceita> p0 = enviar(coleta.token, 0, "g1");
        receber(coleta.id, 1);
        assertThat(p0.get(5, TimeUnit.SECONDS).proximoSeq()).isEqualTo(1);
    }

    @Test
    void soQuemGerouOLinkRecebeEOGravadoPrecisaConferir() throws Exception {
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);
        assertThatThrownBy(() -> servico.proximaParte(coleta.id, UUID.randomUUID(), Duration.ofMillis(10)))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("OUTRO_RECEPTOR"));

        Future<Pedidos.ParteAceita> p0 = enviar(coleta.token, 0, "g1");
        Pedidos.Entrega entrega = servico.proximaParte(coleta.id, quem, Duration.ofSeconds(5)).orElseThrow();
        assertThatThrownBy(() -> servico.confirmarParte(coleta.id, quem, 0, "0".repeat(64)))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(422));
        assertThat(p0).isNotDone();
        servico.confirmarParte(coleta.id, quem, 0, sha(entrega.quadro()));
        assertThat(p0.get(5, TimeUnit.SECONDS).proximoSeq()).isEqualTo(1);

        byte[] corrompido = envelope(1, "g1");
        corrompido[corrompido.length - 3] ^= 0x55;
        assertThatThrownBy(() -> servico.receberParte(coleta.token, 1, "g1", SESSAO, corrompido))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(422));
    }

    @Test
    void voltaParaAntesDeUmArquivoERetomaDepoisDeReiniciar() throws Exception {
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);
        Future<Pedidos.ParteAceita> p0 = enviar(coleta.token, 0, "g1");
        Future<Pedidos.ParteAceita> p1 = enviar(coleta.token, 1, "g1");
        receber(coleta.id, 2);
        p0.get(5, TimeUnit.SECONDS);
        p1.get(5, TimeUnit.SECONDS);

        // o sistema reinicia: o andamento volta dos dados de controle
        servico = novoServico();
        Pedidos.Info info = servico.info(coleta.token, SESSAO);
        assertThat(info.proximoSeq()).isEqualTo(2);
        assertThat(info.cursor().path("caminho").asText()).isEqualTo("sislojas3/Data/CLIENTES.K1");

        // a página pede para voltar ao começo de CLIENTES.DAT (mudou): fica só o pedaço 0
        Pedidos.Info voltou = servico.iniciarGeracao(coleta.token, geracao("g2", "sislojas3/Data/CLIENTES.DAT"), SESSAO);
        assertThat(voltou.proximoSeq()).isEqualTo(1);
        assertThat(voltou.cursor().path("caminho").asText()).isEqualTo("sislojas3/Data/CLIENTES.DAT");

        servico.proximaParte(coleta.id, quem, Duration.ofMillis(10)); // a tela de quem recebe está aberta
        Future<Pedidos.ParteAceita> n1 = enviar(coleta.token, 1, "g2");
        Future<Pedidos.ParteAceita> n2 = enviar(coleta.token, 2, "g2");
        receber(coleta.id, 2);
        n1.get(5, TimeUnit.SECONDS);
        n2.get(5, TimeUnit.SECONDS);
        servico.concluir(coleta.token, new Pedidos.Conclusao("g2", 3), SESSAO);
        String fimDoTar = new String(Zstd.decompress(servico.parteFinal(coleta.id, quem), 100_000), StandardCharsets.UTF_8);
        assertThat(fimDoTar).contains("_coleta/manifesto.json").contains("CLIENTES.K1").contains("CLIENTES.DAT");
    }

    @Test
    void linkDesconhecidoCanceladoEHistorico() throws Exception {
        assertThatThrownBy(() -> servico.info("A".repeat(43), SESSAO))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> servico.info("../../etc", SESSAO))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(404));
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);
        Future<Pedidos.ParteAceita> p0 = enviar(coleta.token, 0, "g1");
        Thread.sleep(100);
        servico.cancelar(coleta.id);
        assertThatThrownBy(() -> p0.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ErroDeColeta.class);
        assertThatThrownBy(() -> servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(410));
        servico.apagar(coleta.id);
        assertThat(servico.listar()).isEmpty();
    }

    @Test
    void linkVazadoNaoServeDepoisQueAColetaComecou() throws Exception {
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);
        String intruso = "I".repeat(43);
        assertThat(servico.info(coleta.token, intruso).emOutroComputador()).isTrue();
        assertThat(servico.info(coleta.token, SESSAO).emOutroComputador()).isFalse();
        assertThatThrownBy(() -> servico.iniciarGeracao(coleta.token, geracao("g9", null), intruso))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("OUTRA_SESSAO"));
        assertThatThrownBy(() -> servico.prepararEnvio(coleta.token, "g1", intruso))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("OUTRA_SESSAO"));
        assertThatThrownBy(() -> servico.receberParte(coleta.token, 0, "g1", intruso, envelope(0, "g1")))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("OUTRA_SESSAO"));
        assertThatThrownBy(() -> servico.prepararEnvio(coleta.token, "g1", null))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("SEM_SESSAO"));
        // só quem gerou o link libera; depois, o outro computador continua
        assertThatThrownBy(() -> servico.liberar(coleta.id, UUID.randomUUID()))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("OUTRO_RECEPTOR"));
        servico.liberar(coleta.id, quem);
        servico.iniciarGeracao(coleta.token, geracao("g2", null), intruso);
        assertThatThrownBy(() -> servico.prepararEnvio(coleta.token, "g2", SESSAO))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("OUTRA_SESSAO"));
    }

    @Test
    void linkInventadoEhRecusadoAntesDeLerOCorpoEPedacoSemCifraNaoEntra() throws Exception {
        assertThatThrownBy(() -> servico.prepararEnvio("Z".repeat(43), "g1", SESSAO))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("LINK_INVALIDO"));
        Coleta coleta = servico.criar("Loja Teste", quem, "eduardo");
        servico.iniciarGeracao(coleta.token, geracao("g1", null), SESSAO);
        byte[] quadro = Zstd.compress("conteudo em claro".repeat(50).getBytes(StandardCharsets.UTF_8), 3);
        ObjectNode cab = json.createObjectNode();
        cab.put("seq", 0);
        cab.put("geracao", "g1");
        cab.put("cifrado", false);
        cab.put("shaQuadro", Envelope.sha256(quadro));
        byte[] adulterado = Envelope.montar(cab, quadro, json);
        assertThatThrownBy(() -> servico.receberParte(coleta.token, 0, "g1", SESSAO, adulterado))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("PEDACO_SEM_CIFRA"));
    }

    @Test
    void tetoDaColeta() {
        assertThat(new ConfiguracaoDoBackup(pasta, 0, "", "", false, 48, 3, 1, 32, 90, 0).limiteColetaBytes())
                .as("0 = sem teto").isEqualTo(Long.MAX_VALUE);
        assertThat(new ConfiguracaoDoBackup(pasta, 0, "", "", false, 48, 3, 1, 32, 90, 200).limiteColetaBytes())
                .isEqualTo(200L * 1024 * 1024 * 1024);
    }
}
