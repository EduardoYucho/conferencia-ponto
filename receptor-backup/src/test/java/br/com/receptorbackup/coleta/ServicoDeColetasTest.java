package br.com.receptorbackup.coleta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.luben.zstd.Zstd;

import br.com.receptorbackup.cliente.Cliente;
import br.com.receptorbackup.cliente.RepositorioDeClientes;
import br.com.receptorbackup.config.Propriedades;

/**
 * O recebimento sem a página: os pedaços são montados aqui como a página monta (tar em blocos, cada bloco um quadro
 * zstd, com o cursor e os SHA-256 no cabeçalho). O teste de ponta a ponta com a página de verdade está em
 * src/test/js/ponta-a-ponta.mjs.
 */
class ServicoDeColetasTest {

    @TempDir
    Path pasta;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private ServicoDeColetas servico;
    private RepositorioDeClientes clientes;
    private Propriedades propriedades;

    @BeforeEach
    void preparar() {
        propriedades = new Propriedades(pasta.resolve("dados"), 0, 0, "", "", 48, 3, 1, 32, 0, 0, true);
        clientes = new RepositorioDeClientes(propriedades, json);
        clientes.salvar(null, new Cliente("Loja Teste", "G:\\sislojas3", List.of()));
        servico = novoServico();
    }

    @AfterEach
    void encerrar() {
        servico.parar();
    }

    private ServicoDeColetas novoServico() {
        ServicoDeColetas s = new ServicoDeColetas(propriedades, new RepositorioDeColetas(propriedades, json), clientes, json);
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
            // só a entrada (cabeçalho, dados e preenchimento): o fim do .tar quem põe é o Receptor
            byte[] completo = tar.toByteArray();
            long usado = 512 + ((arquivo.conteudo().length + 511) / 512) * 512L;
            byte[] bloco = java.util.Arrays.copyOf(completo, (int) usado);
            byte[] quadro = Zstd.compress(bloco, 3);
            ObjectNode cab = json.createObjectNode();
            cab.put("seq", seq);
            cab.put("geracao", geracao);
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

    private Coleta esperarConferencia(String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Coleta c = servico.buscar(id);
            if (c.situacao == Coleta.Situacao.INTEGRA || c.situacao == Coleta.Situacao.DIVERGENTE) {
                return c;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("conferência não terminou");
    }

    @Test
    void pedacosForaDeOrdemDuplicadosEConclusaoIntegra() throws Exception {
        Coleta coleta = servico.criar("Loja Teste");
        servico.iniciarGeracao(coleta.token, geracao("g1", null));

        assertThat(servico.receberParte(coleta.token, 1, "g1", envelope(1, "g1")).proximoSeq()).isZero();
        assertThat(servico.receberParte(coleta.token, 2, "g1", envelope(2, "g1")).proximoSeq()).isZero();
        assertThat(servico.receberParte(coleta.token, 0, "g1", envelope(0, "g1")).proximoSeq()).isEqualTo(3);
        // repetido (a resposta anterior se perdeu): aceito sem duplicar
        assertThat(servico.receberParte(coleta.token, 1, "g1", envelope(1, "g1")).proximoSeq()).isEqualTo(3);

        assertThatThrownBy(() -> servico.concluir(coleta.token, new Pedidos.Conclusao("g1", 4)))
                .isInstanceOf(ErroDeColeta.class).hasMessageContaining("faltam");
        servico.concluir(coleta.token, new Pedidos.Conclusao("g1", 3));

        Coleta conferida = esperarConferencia(coleta.id);
        assertThat(conferida.situacao).isEqualTo(Coleta.Situacao.INTEGRA);
        assertThat(conferida.resumo.arquivos()).isEqualTo(3);
        Path arquivo = servico.arquivoFinal(conferida);
        assertThat(arquivo).exists().hasFileName(coleta.nome + ".tar.zst");
        assertThat(arquivo.resolveSibling(coleta.nome + ".json")).exists();

        Path extraido = pasta.resolve("extraido");
        LeitorDoBackup.extrair(arquivo, extraido, b -> {
        });
        for (Arquivo a : arquivos) {
            assertThat(Files.readAllBytes(extraido.resolve(a.caminho()))).isEqualTo(a.conteudo());
        }
        assertThat(extraido.resolve(LeitorDoBackup.MANIFESTO)).exists();
    }

    @Test
    void pedacoCorrompidoEGeracaoAntigaSaoRecusados() throws Exception {
        Coleta coleta = servico.criar("Loja Teste");
        servico.iniciarGeracao(coleta.token, geracao("g1", null));
        byte[] corrompido = envelope(0, "g1");
        corrompido[corrompido.length - 3] ^= 0x55;
        assertThatThrownBy(() -> servico.receberParte(coleta.token, 0, "g1", corrompido))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(422));

        servico.iniciarGeracao(coleta.token, geracao("g2", null));
        assertThatThrownBy(() -> servico.receberParte(coleta.token, 0, "g1", envelope(0, "g1")))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.codigo()).isEqualTo("GERACAO_ANTIGA"));
    }

    @Test
    void voltaParaAntesDeUmArquivoERetomaDepoisDeReiniciar() throws Exception {
        Coleta coleta = servico.criar("Loja Teste");
        servico.iniciarGeracao(coleta.token, geracao("g1", null));
        servico.receberParte(coleta.token, 0, "g1", envelope(0, "g1"));
        servico.receberParte(coleta.token, 1, "g1", envelope(1, "g1"));

        // o Receptor reinicia: o andamento volta do disco
        servico.parar();
        servico = novoServico();
        Pedidos.Info info = servico.info(coleta.token);
        assertThat(info.proximoSeq()).isEqualTo(2);
        assertThat(info.cursor().path("caminho").asText()).isEqualTo("sislojas3/Data/CLIENTES.K1");

        // a página pede para voltar ao começo de CLIENTES.DAT (mudou): fica só o pedaço 0
        Pedidos.Info voltou = servico.iniciarGeracao(coleta.token, geracao("g2", "sislojas3/Data/CLIENTES.DAT"));
        assertThat(voltou.proximoSeq()).isEqualTo(1);
        assertThat(voltou.cursor().path("caminho").asText()).isEqualTo("sislojas3/Data/CLIENTES.DAT");

        servico.receberParte(coleta.token, 1, "g2", envelope(1, "g2"));
        servico.receberParte(coleta.token, 2, "g2", envelope(2, "g2"));
        servico.concluir(coleta.token, new Pedidos.Conclusao("g2", 3));
        assertThat(esperarConferencia(coleta.id).situacao).isEqualTo(Coleta.Situacao.INTEGRA);
    }

    @Test
    void linkDesconhecidoECancelado() throws Exception {
        assertThatThrownBy(() -> servico.info("A".repeat(43)))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> servico.info("../../etc"))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(404));
        Coleta coleta = servico.criar("Loja Teste");
        servico.cancelar(coleta.id);
        assertThatThrownBy(() -> servico.iniciarGeracao(coleta.token, geracao("g1", null)))
                .isInstanceOfSatisfying(ErroDeColeta.class, e -> assertThat(e.status()).isEqualTo(410));
    }

    @Test
    void limpezaMantemAUltimaDeCadaCliente() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Coleta coleta = servico.criar("Loja Teste");
            servico.iniciarGeracao(coleta.token, geracao("g", null));
            for (int s = 0; s < 3; s++) {
                servico.receberParte(coleta.token, s, "g", envelope(s, "g"));
            }
            servico.concluir(coleta.token, new Pedidos.Conclusao("g", 3));
            esperarConferencia(coleta.id).concluidaEm = java.time.Instant.now().minus(java.time.Duration.ofDays(30 - i));
            ids.add(coleta.id);
            Thread.sleep(5);
        }
        assertThat(servico.limpar(7, true)).isEqualTo(1);
        assertThat(servico.listar()).extracting(c -> c.id).containsExactly(ids.get(1));
    }
}
