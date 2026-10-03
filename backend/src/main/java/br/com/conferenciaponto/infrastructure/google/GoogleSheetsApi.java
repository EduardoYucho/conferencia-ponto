package br.com.conferenciaponto.infrastructure.google;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import br.com.conferenciaponto.application.planilha.PlanilhaRemotaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chamadas à API do Google Sheets (v4) com a conta de serviço: troca a chave por um token de acesso (guardado
 * até perto de vencer) e traduz as recusas do Google em mensagens que dizem o que fazer.
 */
@Component
public class GoogleSheetsApi {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsApi.class);

    /** Uma aba da planilha no Google. */
    public record AbaRemota(int id, String titulo, int indice) {
    }

    public record Metadados(String titulo, List<AbaRemota> abas) {

        public boolean tem(int id) {
            return abas.stream().anyMatch(a -> a.id() == id);
        }
    }

    static final String ESCOPO = "https://www.googleapis.com/auth/spreadsheets";

    private final GoogleProperties properties;
    private final CredencialGoogle credencial;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;

    private String token;
    private String tokenDe;
    private Instant tokenValeAte = Instant.EPOCH;

    public GoogleSheetsApi(GoogleProperties properties, CredencialGoogle credencial, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.credencial = credencial;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Pede um token com a chave informada: confere que o Google aceita a chave antes de gravá-la. */
    public synchronized void conferir(CredencialGoogle.Chave chave) {
        guardar(chave, pedirToken(chave));
    }

    public synchronized void esquecerToken() {
        token = null;
        tokenValeAte = Instant.EPOCH;
    }

    public Metadados metadados(String planilhaId) {
        JsonNode no = chamar("GET", "/v4/spreadsheets/" + planilhaId
                + "?fields=" + codificar("properties.title,sheets.properties(sheetId,title,index)"), null);
        List<AbaRemota> abas = new ArrayList<>();
        for (JsonNode aba : no.path("sheets")) {
            JsonNode p = aba.path("properties");
            abas.add(new AbaRemota(p.path("sheetId").asInt(), p.path("title").asText(""), p.path("index").asInt()));
        }
        return new Metadados(no.path("properties").path("title").asText(""), abas);
    }

    /** A aba não tem nenhum valor? */
    public boolean vazia(String planilhaId, String tituloDaAba) {
        String intervalo = "'" + tituloDaAba.replace("'", "''") + "'";
        JsonNode no = chamar("GET", "/v4/spreadsheets/" + planilhaId + "/values/" + codificar(intervalo), null);
        return !no.has("values") || no.path("values").isEmpty();
    }

    /** Aplica as requisições em ordem, numa única operação: ou todas valem, ou nenhuma. */
    public void atualizar(String planilhaId, List<Map<String, Object>> requisicoes) {
        if (requisicoes.isEmpty()) {
            return;
        }
        chamar("POST", "/v4/spreadsheets/" + planilhaId + ":batchUpdate", Map.of("requests", requisicoes));
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode chamar(String metodo, String caminho, Object corpo) {
        CredencialGoogle.Chave chave = credencial.chave().orElseThrow(() -> new PlanilhaRemotaException(
                "GOOGLE_SEM_CONTA", "A integração com o Google Sheets não está configurada.", false));
        HttpResponse<String> resposta = enviar(metodo, caminho, corpo, token(chave));
        if (resposta.statusCode() == 401) {
            // token vencido ou revogado antes da hora: pede outro e tenta uma vez
            esquecerToken();
            resposta = enviar(metodo, caminho, corpo, token(chave));
        }
        if (resposta.statusCode() / 100 != 2) {
            throw recusa(resposta, chave);
        }
        try {
            return json.readTree(resposta.body() == null || resposta.body().isBlank() ? "{}" : resposta.body());
        } catch (IOException e) {
            throw new PlanilhaRemotaException("GOOGLE_RESPOSTA_INVALIDA", "O Google devolveu uma resposta ilegível.", true, e);
        }
    }

    private HttpResponse<String> enviar(String metodo, String caminho, Object corpo, String tokenDeAcesso) {
        try {
            HttpRequest.Builder requisicao = HttpRequest.newBuilder(URI.create(base(properties.urlApi()) + caminho))
                    .timeout(properties.tempoLimite())
                    .header("Authorization", "Bearer " + tokenDeAcesso)
                    .header("Accept", "application/json");
            if (corpo == null) {
                requisicao.method(metodo, HttpRequest.BodyPublishers.noBody());
            } else {
                requisicao.header("Content-Type", "application/json; charset=UTF-8")
                        .method(metodo, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(corpo)));
            }
            return http.send(requisicao.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw semConexao(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw semConexao(e);
        }
    }

    private synchronized String token(CredencialGoogle.Chave chave) {
        if (token == null || !chave.email().equals(tokenDe) || !clock.instant().isBefore(tokenValeAte)) {
            guardar(chave, pedirToken(chave));
        }
        return token;
    }

    private void guardar(CredencialGoogle.Chave chave, JsonNode resposta) {
        token = resposta.path("access_token").asText();
        tokenDe = chave.email();
        // renova 5 minutos antes de vencer
        tokenValeAte = clock.instant().plusSeconds(Math.max(60, resposta.path("expires_in").asLong(3600) - 300));
    }

    private JsonNode pedirToken(CredencialGoogle.Chave chave) {
        String assercao = credencial.assercao(chave, ESCOPO, properties.urlToken(), clock.instant());
        String formulario = "grant_type=" + codificar("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + codificar(assercao);
        HttpResponse<String> resposta;
        try {
            resposta = http.send(HttpRequest.newBuilder(URI.create(properties.urlToken()))
                    .timeout(properties.tempoLimite())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formulario))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw semConexao(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw semConexao(e);
        }
        JsonNode no;
        try {
            no = json.readTree(resposta.body() == null || resposta.body().isBlank() ? "{}" : resposta.body());
        } catch (IOException e) {
            no = json.createObjectNode();
        }
        if (resposta.statusCode() / 100 == 2 && no.hasNonNull("access_token")) {
            return no;
        }
        if (resposta.statusCode() == 429 || resposta.statusCode() >= 500) {
            throw new PlanilhaRemotaException("GOOGLE_INDISPONIVEL",
                    "O Google está indisponível no momento (HTTP %d). O sistema tenta de novo sozinho."
                            .formatted(resposta.statusCode()), true);
        }
        String detalhe = no.path("error_description").asText(no.path("error").asText("HTTP " + resposta.statusCode()));
        throw new PlanilhaRemotaException("GOOGLE_CHAVE_RECUSADA",
                "O Google recusou a chave da conta de serviço (%s). Confira a data e a hora do computador; se estiverem "
                        .formatted(detalhe) + "certas, crie uma chave nova no Google Cloud e envie de novo.", false);
    }

    /** Traduz o erro da API para o que a pessoa precisa fazer. */
    private PlanilhaRemotaException recusa(HttpResponse<String> resposta, CredencialGoogle.Chave chave) {
        int status = resposta.statusCode();
        String mensagem = "";
        String motivo = "";
        try {
            JsonNode erro = json.readTree(resposta.body() == null ? "{}" : resposta.body()).path("error");
            mensagem = erro.path("message").asText("");
            motivo = erro.path("status").asText("");
            for (JsonNode detalhe : erro.path("details")) {
                if (detalhe.hasNonNull("reason")) {
                    motivo = detalhe.path("reason").asText();
                }
            }
        } catch (IOException e) {
            // corpo que não é JSON (proxy, página de erro): vale o status
        }
        if (status == 429 || status >= 500) {
            return new PlanilhaRemotaException("GOOGLE_INDISPONIVEL",
                    "O Google está indisponível ou limitou as gravações (HTTP %d). O sistema tenta de novo sozinho."
                            .formatted(status), true);
        }
        boolean apiDesativada = "SERVICE_DISABLED".equals(motivo) || mensagem.contains("has not been used in project")
                || mensagem.contains("it is disabled");
        if (status == 403 && apiDesativada) {
            return new PlanilhaRemotaException("GOOGLE_API_DESATIVADA",
                    "A API do Google Sheets não está ativada no projeto \"%s\" do Google Cloud. Ative em "
                            .formatted(chave.projeto())
                            + "console.cloud.google.com/apis/library/sheets.googleapis.com e tente de novo em um minuto.",
                    false);
        }
        if (status == 403) {
            return new PlanilhaRemotaException("GOOGLE_SEM_PERMISSAO",
                    "A planilha não está compartilhada com %s como Editor. No Google Sheets, clique em Compartilhar, "
                            .formatted(chave.email()) + "adicione esse e-mail como Editor e tente de novo.", false);
        }
        if (status == 404) {
            return new PlanilhaRemotaException("GOOGLE_PLANILHA_NAO_ENCONTRADA",
                    "Planilha não encontrada no Google. Confira o link.", false);
        }
        if (status == 400 && mensagem.contains("not supported for this document")) {
            return new PlanilhaRemotaException("GOOGLE_ARQUIVO_NAO_E_PLANILHA",
                    "O link é de um arquivo do Excel aberto no Google Drive, não de uma planilha do Google. Crie uma "
                            + "planilha nova em sheets.new (ou use Arquivo > Salvar como Planilhas Google).", false);
        }
        // o detalhe do Google vem em inglês e é técnico: vai para o log; a pessoa lê o que fazer
        log.warn("O Google recusou a gravação (HTTP {}): {}", status, mensagem.isBlank() ? "sem detalhes" : mensagem);
        return new PlanilhaRemotaException("GOOGLE_RECUSOU",
                "O Google recusou a gravação da planilha (código %d). Tente \"Atualizar agora\"; se continuar, avise o administrador."
                        .formatted(status), false);
    }

    private static PlanilhaRemotaException semConexao(Exception e) {
        log.warn("Sem conexão com o Google: {}", e.toString());
        return new PlanilhaRemotaException("GOOGLE_SEM_CONEXAO",
                "Sem conexão com o Google. O sistema tenta de novo sozinho; se continuar, confira a internet do servidor.",
                true, e);
    }

    private static String base(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String codificar(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
