package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** O painel baixado de um servidor HTTP local (nenhum acesso à internet). */
class PainelDaSvrsTest {

    private static final Instant AGORA = Instant.parse("2026-11-09T19:55:00Z");
    private HttpServer servidor;
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1)).build();

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.createContext("/Nfe/Disponibilidade", troca -> {
            userAgent.set(troca.getRequestHeaders().getFirst("User-Agent"));
            byte[] corpo = LeitorDoPainelSvrsTest.pagina("painel-nfe.html");
            troca.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            troca.sendResponseHeaders(200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.createContext("/Cte/Disponibilidade", troca -> {
            byte[] corpo = LeitorDoPainelSvrsTest.pagina("painel-cte.html");
            troca.getResponseHeaders().add("Content-Type", "text/html");
            troca.sendResponseHeaders(200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.createContext("/Mdfe/Disponibilidade", troca -> {
            troca.sendResponseHeaders(503, -1);
            troca.close();
        });
        servidor.createContext("/Nfce/Disponibilidade", troca -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    private PainelDaSvrs painel(Documento documento, String caminho) {
        return new PainelDaSvrs(documento, "http://127.0.0.1:" + servidor.getAddress().getPort() + caminho, http,
                Duration.ofSeconds(1), Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("Página lida: as leituras, o aviso do painel e a identificação do sistema no pedido")
    void lida() {
        FonteDeLeituras.Resultado r = painel(Documento.NFE, "/Nfe/Disponibilidade").ler(AGORA);

        assertThat(r.fonte()).isEqualTo("painel-svrs-nfe");
        assertThat(r.alcancou()).isTrue();
        assertThat(r.erro()).isNull();
        assertThat(r.leituras()).hasSize(8);
        assertThat(r.documentoDoAviso()).isEqualTo(Documento.NFE);
        assertThat(r.aviso()).startsWith("Não há manutenção prevista");
        assertThat(userAgent.get()).startsWith("ConferenciaPonto-MonitorFiscal/");
    }

    @Test
    @DisplayName("Sem charset no cabeçalho, vale o da página (windows-1252 no CT-e)")
    void charsetDaPagina() {
        FonteDeLeituras.Resultado r = painel(Documento.CTE, "/Cte/Disponibilidade").ler(AGORA);

        assertThat(r.erro()).isNull();
        assertThat(r.aviso()).contains("Haverá manutenção");
    }

    @Test
    @DisplayName("HTTP 503: alcançou o portal, mas é fonte com problema; demorou: não alcançou; porta fechada: não conectou")
    void problemas() throws IOException {
        FonteDeLeituras.Resultado indisponivel = painel(Documento.MDFE, "/Mdfe/Disponibilidade").ler(AGORA);
        assertThat(indisponivel.alcancou()).isTrue();
        assertThat(indisponivel.erro()).isEqualTo("O portal respondeu HTTP 503.");
        assertThat(indisponivel.leituras()).isEmpty();
        assertThat(indisponivel.documentoDoAviso()).as("sem leitura, o aviso anterior fica").isNull();

        FonteDeLeituras.Resultado lento = painel(Documento.NFCE, "/Nfce/Disponibilidade").ler(AGORA);
        assertThat(lento.alcancou()).isFalse();
        assertThat(lento.erro()).isEqualTo("O portal não respondeu em 1 s.");

        PainelDaSvrs fechado = new PainelDaSvrs(Documento.NFE, "http://127.0.0.1:" + ServidoresDeTeste.fechada() + "/",
                http, Duration.ofSeconds(1), Duration.ofMinutes(30));
        FonteDeLeituras.Resultado semConexao = fechado.ler(AGORA);
        assertThat(semConexao.alcancou()).isFalse();
        assertThat(semConexao.erro()).startsWith("Não conectou ao portal");
    }
}
