package br.com.conferenciaponto.modulos.atendimento.infrastructure.download;

import br.com.conferenciaponto.modulos.atendimento.ServidorDeAnexos;
import br.com.conferenciaponto.modulos.atendimento.ServidorDeAnexos.Modo;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.FalhaNoDownload;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O download contra um servidor HTTP local (no lugar do armazenamento do Digisac). */
class BaixadorHttpTest {

    private static final byte[] CONTEUDO = new byte[300_000];

    static {
        new Random(42).nextBytes(CONTEUDO);
    }

    @TempDir
    Path pasta;

    private final ServidorDeAnexos servidor = new ServidorDeAnexos();
    /** Nos testes: HTTP e o próprio computador permitidos (em produção, só HTTPS e nada da rede interna). */
    private final BaixadorHttp baixador = new BaixadorHttp(new PoliticaDeRede(List.of("127.0.0.1"), false, true, null),
            Duration.ofSeconds(2), Duration.ofMillis(500));

    @AfterEach
    void parar() {
        servidor.close();
    }

    private Path parcial() {
        return pasta.resolve("arquivos").resolve(".parcial-teste");
    }

    private static void falhaComo(Runnable acao, String codigo, boolean temporaria) {
        assertThatThrownBy(acao::run).isInstanceOf(FalhaNoDownload.class)
                .hasFieldOrPropertyWithValue("codigo", codigo)
                .hasFieldOrPropertyWithValue("temporaria", temporaria);
    }

    @Test
    void baixaOArquivoInteiro() throws IOException {
        servidor.arquivo("/anexo.jpeg", CONTEUDO);

        long tamanho = baixador.baixar(servidor.url("/anexo.jpeg?X-Amz-Signature=segredo"), parcial(), 10_000_000);

        assertThat(tamanho).isEqualTo(CONTEUDO.length);
        assertThat(Files.readAllBytes(parcial())).isEqualTo(CONTEUDO);
    }

    @Test
    void conexaoQueCaiNoMeioContinuaDeOndeParou() throws IOException {
        servidor.arquivo("/audio.oga", CONTEUDO, Modo.CAI_NA_PRIMEIRA);

        baixador.baixar(servidor.url("/audio.oga"), parcial(), 10_000_000);

        assertThat(Files.readAllBytes(parcial())).isEqualTo(CONTEUDO);
        List<ServidorDeAnexos.Pedido> pedidos = servidor.pedidos();
        assertThat(pedidos).hasSize(2);
        assertThat(pedidos.get(0).range()).isNull();
        assertThat(pedidos.get(1).range()).isEqualTo("bytes=" + CONTEUDO.length / 2 + "-");
    }

    @Test
    void continuaUmDownloadDeUmaTentativaAnterior() throws IOException {
        servidor.arquivo("/video.mp4", CONTEUDO);
        Files.createDirectories(parcial().getParent());
        Files.write(parcial(), Arrays.copyOf(CONTEUDO, 1000));

        baixador.baixar(servidor.url("/video.mp4"), parcial(), 10_000_000);

        assertThat(Files.readAllBytes(parcial())).isEqualTo(CONTEUDO);
        assertThat(servidor.pedidos()).singleElement().extracting(ServidorDeAnexos.Pedido::range).isEqualTo("bytes=1000-");
    }

    @Test
    void servidorQueIgnoraORangeRecomecaDoZero() throws IOException {
        servidor.arquivo("/doc.pdf", CONTEUDO, Modo.IGNORA_RANGE);
        Files.createDirectories(parcial().getParent());
        Files.write(parcial(), new byte[] {1, 2, 3});

        baixador.baixar(servidor.url("/doc.pdf"), parcial(), 10_000_000);

        assertThat(Files.readAllBytes(parcial())).isEqualTo(CONTEUDO);
    }

    @Test
    void recusasDoServidor() {
        servidor.status("/vencido", 403).status("/sumiu", 404);

        assertThatThrownBy(() -> baixador.baixar(servidor.url("/vencido"), parcial(), 10_000))
                .hasFieldOrPropertyWithValue("codigo", "DOWNLOAD_RECUSADO")
                .hasFieldOrPropertyWithValue("status", 403)
                .hasFieldOrPropertyWithValue("temporaria", false);
        falhaComo(() -> baixador.baixar(servidor.url("/sumiu"), parcial(), 10_000), "DOWNLOAD_RECUSADO", false);
    }

    @Test
    void servidorOcupadoETemporario() {
        servidor.status("/ocupado", 503);

        falhaComo(() -> baixador.baixar(servidor.url("/ocupado"), parcial(), 10_000), "DOWNLOAD_FALHOU", true);
    }

    @Test
    void naoSegueRedirecionamento() {
        servidor.arquivo("/destino", CONTEUDO).redireciona("/anexo", servidor.url("/destino"));

        falhaComo(() -> baixador.baixar(servidor.url("/anexo"), parcial(), 10_000_000), "DOWNLOAD_RECUSADO", false);
        assertThat(servidor.pedidosPara("/destino")).isZero();
    }

    @Test
    void servidorLentoDemaisETemporario() {
        servidor.arquivo("/lento", CONTEUDO, Modo.LENTO);

        falhaComo(() -> baixador.baixar(servidor.url("/lento"), parcial(), 10_000_000), "DOWNLOAD_FALHOU", true);
    }

    @Test
    void arquivoGrandeDemaisParaSemGuardarNada() throws IOException {
        servidor.arquivo("/grande", CONTEUDO).arquivo("/grande-sem-tamanho", CONTEUDO, Modo.SEM_TAMANHO);

        falhaComo(() -> baixador.baixar(servidor.url("/grande"), parcial(), 1000), "ARQUIVO_GRANDE_DEMAIS", false);
        assertThat(parcial()).doesNotExist();
        falhaComo(() -> baixador.baixar(servidor.url("/grande-sem-tamanho"), parcial(), 1000), "ARQUIVO_GRANDE_DEMAIS", false);
        assertThat(parcial()).doesNotExist();
    }

    @Test
    void comAPoliticaDeProducaoNadaDaRedeInternaNemSemHttps() {
        BaixadorHttp producao = new BaixadorHttp(PoliticaDeRede.producao(List.of("127.0.0.1", "localhost")),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        servidor.arquivo("/anexo", CONTEUDO);

        falhaComo(() -> producao.baixar(servidor.url("/anexo"), parcial(), 10_000_000), "HOST_NAO_PERMITIDO", false);
        BaixadorHttp semHttps = new BaixadorHttp(new PoliticaDeRede(List.of("localhost"), false, false, null),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        falhaComo(() -> semHttps.baixar(servidor.url("/anexo").replace("127.0.0.1", "localhost"), parcial(), 10_000_000),
                "HOST_NAO_PERMITIDO", false);
        assertThat(servidor.pedidos()).isEmpty();
    }

    @Test
    void linkInvalido() {
        falhaComo(() -> baixador.baixar("isto não é um link", parcial(), 10), "DOWNLOAD_RECUSADO", false);
    }

    @Test
    void oLogNuncaTemOLink() {
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> linhas = new ListAppender<>();
        linhas.start();
        logger.addAppender(linhas);
        servidor.arquivo("/caminho-secreto/a.jpeg", CONTEUDO).status("/caminho-secreto/b.jpeg", 403);
        try {
            baixador.baixar(servidor.url("/caminho-secreto/a.jpeg?X-Amz-Signature=assinatura"), parcial(), 10_000_000);
            try {
                baixador.baixar(servidor.url("/caminho-secreto/b.jpeg?X-Amz-Signature=assinatura"), pasta.resolve("b"), 10_000);
            } catch (FalhaNoDownload esperada) {
                assertThat(esperada.getMessage()).doesNotContain("caminho-secreto").doesNotContain("assinatura");
            }
        } finally {
            logger.detachAppender(linhas);
        }

        assertThat(linhas.list).isNotEmpty();
        assertThat(linhas.list).allSatisfy(l -> assertThat(l.getFormattedMessage())
                .doesNotContain("caminho-secreto").doesNotContain("assinatura"));
    }
}
