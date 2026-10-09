package br.com.conferenciaponto.modulos.atendimento.infrastructure.gemini;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * API REST do Gemini (generativelanguage.googleapis.com), chamada com o HttpClient do Java. Nesta etapa só o teste
 * da chave: listar um modelo, que não gasta cota de geração.
 *
 * <p>A chave vai só no cabeçalho {@code x-goog-api-key}: nunca na URL (que aparece em logs de acesso) e nunca no
 * log. Do Google, o log guarda só o código HTTP e o motivo.
 */
@Component
public class ClienteGemini implements VerificadorDeChave {

    private static final Logger log = LoggerFactory.getLogger(ClienteGemini.class);

    private final GeminiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public ClienteGemini(GeminiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.tempoConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Resultado testar(String chave) {
        HttpRequest pedido = HttpRequest.newBuilder(endereco("/v1beta/models?pageSize=1"))
                .header("x-goog-api-key", chave)
                .header("Accept", "application/json")
                .timeout(properties.tempoTeste())
                .GET()
                .build();
        try {
            HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
            Resultado resultado = interpretar(resposta.statusCode(), resposta.body());
            log.info("Teste da chave do Gemini: HTTP {} -> {}", resposta.statusCode(), resultado.tipo());
            return resultado;
        } catch (HttpTimeoutException e) {
            log.warn("Teste da chave do Gemini: o Google não respondeu a tempo ({})", properties.tempoTeste());
            return new Resultado(Tipo.INDISPONIVEL, "O Google não respondeu a tempo.");
        } catch (IOException e) {
            log.warn("Teste da chave do Gemini: sem conexão com o Google ({})", e.getClass().getSimpleName());
            return new Resultado(Tipo.SEM_INTERNET, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resultado(Tipo.INDISPONIVEL, "O teste foi interrompido.");
        }
    }

    /** Traduz a resposta do Google (formato de erro das APIs do Google: error.code, error.status, details[].reason). */
    Resultado interpretar(int status, String corpo) {
        if (status == 200) {
            return new Resultado(Tipo.VALIDA, null);
        }
        String motivo = motivo(corpo);
        if (status == 429) {
            return new Resultado(Tipo.SEM_COTA, "A cota por minuto renova em instantes; a do dia, por volta das 4h–5h "
                    + "(meia-noite no horário do Pacífico).");
        }
        if (status == 400 || status == 401 || status == 403) {
            if (motivo.contains("SERVICE_DISABLED") || motivo.contains("ACCESS_TOKEN_SCOPE")) {
                return new Resultado(Tipo.RECUSADA, "A API do Gemini (Generative Language API) não está ativada no "
                        + "projeto dessa chave.");
            }
            if (motivo.contains("BLOCKED") || motivo.contains("REFERER") || motivo.contains("IP_ADDRESS")) {
                return new Resultado(Tipo.RECUSADA, "A chave tem uma restrição (de site, de IP ou de API) que não deixa "
                        + "este servidor usá-la. Tire a restrição ou crie outra chave.");
            }
            if (motivo.contains("API_KEY_INVALID") || motivo.contains("API_KEY_EXPIRED") || status == 400) {
                return new Resultado(Tipo.RECUSADA, "A chave é inválida, expirou ou foi apagada.");
            }
            return new Resultado(Tipo.RECUSADA, "Sem permissão para usar a API do Gemini com essa chave (HTTP " + status
                    + ").");
        }
        if (status >= 500) {
            return new Resultado(Tipo.INDISPONIVEL, "O Google respondeu com erro " + status + ".");
        }
        return new Resultado(Tipo.INDISPONIVEL, "Resposta inesperada do Google (HTTP " + status + ").");
    }

    /** status e reasons do erro, em maiúsculas, para comparar; vazio se o corpo não for o JSON de erro do Google. */
    private String motivo(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return "";
        }
        try {
            JsonNode erro = json.readTree(corpo).path("error");
            StringBuilder texto = new StringBuilder(erro.path("status").asText(""));
            for (JsonNode detalhe : erro.path("details")) {
                texto.append(' ').append(detalhe.path("reason").asText(""));
            }
            texto.append(' ').append(erro.path("message").asText(""));
            return texto.toString().toUpperCase(Locale.ROOT).replace(' ', '_');
        } catch (IOException e) {
            return "";
        }
    }

    private URI endereco(String caminho) {
        String base = properties.urlBase().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + caminho);
    }
}
