package br.com.conferenciaponto.modulos.atendimento.infrastructure.gemini;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave.Resultado;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave.Tipo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.GeminiProperties;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** O teste da chave contra um "Google" simulado (servidor HTTP local), com as respostas de erro reais da API. */
class ClienteGeminiTest {

    private static final String CHAVE = "AIzaSyD-chave-de-teste_0123456789abcd";

    private HttpServer google;
    private final AtomicReference<String> chaveRecebida = new AtomicReference<>();
    private final AtomicReference<String> enderecoRecebido = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String corpo = "{\"models\":[{\"name\":\"models/gemini-3.8-flash\"}]}";
    private volatile long atrasoMs = 0;

    @BeforeEach
    void subirOGoogleSimulado() throws IOException {
        google = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        google.createContext("/", troca -> {
            chaveRecebida.set(troca.getRequestHeaders().getFirst("x-goog-api-key"));
            enderecoRecebido.set(troca.getRequestURI().toString());
            try {
                Thread.sleep(atrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, bytes.length);
            troca.getResponseBody().write(bytes);
            troca.close();
        });
        google.start();
    }

    @AfterEach
    void derrubar() {
        google.stop(0);
    }

    private ClienteGemini cliente() {
        return cliente(URI.create("http://127.0.0.1:" + google.getAddress().getPort()));
    }

    private static ClienteGemini cliente(URI base) {
        return new ClienteGemini(new GeminiProperties(base, Duration.ofSeconds(2), Duration.ofMillis(800)),
                new ObjectMapper());
    }

    private static String erro(int codigo, String status, String motivo, String mensagem) {
        return """
                {"error": {"code": %d, "message": "%s", "status": "%s",
                  "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "%s", "domain": "googleapis.com"}]}}
                """.formatted(codigo, mensagem, status, motivo);
    }

    @Test
    void chaveAceitaEnviandoAChaveSoNoCabecalho() {
        Resultado resultado = cliente().testar(CHAVE);

        assertThat(resultado.tipo()).isEqualTo(Tipo.VALIDA);
        assertThat(chaveRecebida.get()).isEqualTo(CHAVE);
        assertThat(enderecoRecebido.get()).startsWith("/v1beta/models").doesNotContain(CHAVE);
    }

    @Test
    void chaveInvalida() {
        status = 400;
        corpo = erro(400, "INVALID_ARGUMENT", "API_KEY_INVALID", "API key not valid. Please pass a valid API key.");

        Resultado resultado = cliente().testar(CHAVE);

        assertThat(resultado.tipo()).isEqualTo(Tipo.RECUSADA);
        assertThat(resultado.detalhe()).contains("inválida");
    }

    @Test
    void apiDesativadaNoProjetoDaChave() {
        status = 403;
        corpo = erro(403, "PERMISSION_DENIED", "SERVICE_DISABLED", "Generative Language API has not been used in project 123");

        Resultado resultado = cliente().testar(CHAVE);

        assertThat(resultado.tipo()).isEqualTo(Tipo.RECUSADA);
        assertThat(resultado.detalhe()).contains("não está ativada");
    }

    @Test
    void chaveComRestricaoDeUso() {
        status = 403;
        corpo = erro(403, "PERMISSION_DENIED", "API_KEY_HTTP_REFERRER_BLOCKED", "Requests from referer <empty> are blocked.");

        Resultado resultado = cliente().testar(CHAVE);

        assertThat(resultado.tipo()).isEqualTo(Tipo.RECUSADA);
        assertThat(resultado.detalhe()).contains("restrição");
    }

    @Test
    void semCota() {
        status = 429;
        corpo = erro(429, "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED", "Quota exceeded.");

        Resultado resultado = cliente().testar(CHAVE);

        assertThat(resultado.tipo()).isEqualTo(Tipo.SEM_COTA);
        assertThat(resultado.detalhe()).contains("Pacífico");
    }

    @Test
    void googleComProblema() {
        status = 503;
        corpo = erro(503, "UNAVAILABLE", "", "The model is overloaded.");

        assertThat(cliente().testar(CHAVE).tipo()).isEqualTo(Tipo.INDISPONIVEL);
    }

    @Test
    void respostaQueNaoEJsonNaoQuebra() {
        status = 403;
        corpo = "<html>proibido</html>";

        assertThat(cliente().testar(CHAVE).tipo()).isEqualTo(Tipo.RECUSADA);
    }

    @Test
    void googleQueNaoRespondeATempo() {
        atrasoMs = 2_000;

        Resultado resultado = cliente().testar(CHAVE);

        assertThat(resultado.tipo()).isEqualTo(Tipo.INDISPONIVEL);
        assertThat(resultado.detalhe()).contains("a tempo");
    }

    @Test
    void semConexaoComOGoogle() throws IOException {
        int portaFechada;
        try (ServerSocket livre = new ServerSocket(0)) {
            portaFechada = livre.getLocalPort();
        }

        assertThat(cliente(URI.create("http://127.0.0.1:" + portaFechada)).testar(CHAVE).tipo()).isEqualTo(Tipo.SEM_INTERNET);
    }

    @Test
    void aChaveNuncaVaiParaOLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(ClienteGemini.class);
        ListAppender<ILoggingEvent> linhas = new ListAppender<>();
        linhas.start();
        logger.addAppender(linhas);
        try {
            cliente().testar(CHAVE);
            status = 400;
            corpo = erro(400, "INVALID_ARGUMENT", "API_KEY_INVALID", "API key not valid.");
            cliente().testar(CHAVE);
        } finally {
            logger.detachAppender(linhas);
        }

        assertThat(linhas.list).isNotEmpty();
        assertThat(linhas.list).allSatisfy(l -> assertThat(l.getFormattedMessage()).doesNotContain(CHAVE));
    }
}
