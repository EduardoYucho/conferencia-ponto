package br.com.conferenciaponto.modulos.atendimento.infrastructure.gemini;

import br.com.conferenciaponto.modulos.atendimento.domain.ia.ArquivoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo.Tipo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A análise pela API REST do Gemini (generativelanguage.googleapis.com/v1beta): generateContent com resposta em JSON
 * no esquema pedido e a Files API com envio retomável (o protocolo "resumable" do Google, em pedaços: se a conexão
 * cair, pergunta quanto chegou e continua dali).
 *
 * <p>A chave vai só no cabeçalho {@code x-goog-api-key}, e só para o host da API: nunca na URL nem no log. Do
 * conteúdo, nada vai para o log; do Google, só o código HTTP, o motivo e os tokens.
 */
@Component
public class ModeloGemini implements ModeloDeIA {

    private static final Logger log = LoggerFactory.getLogger(ModeloGemini.class);

    private static final int PEDACO_PADRAO = 8 * 1024 * 1024;
    private static final int QUEDAS_NO_ENVIO = 3;
    private static final Pattern NOME_DO_MODELO = Pattern.compile("[A-Za-z0-9._\\-]{1,80}");
    private static final Pattern NOME_DO_ARQUIVO = Pattern.compile("files/[A-Za-z0-9_\\-]{1,100}");
    private static final Set<String> MOTIVOS_DE_BLOQUEIO = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "IMAGE_SAFETY", "LANGUAGE", "OTHER", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION");
    private static final Map<String, String> MOTIVOS = Map.of(
            "SAFETY", "segurança",
            "IMAGE_SAFETY", "segurança da imagem",
            "RECITATION", "reprodução de conteúdo protegido",
            "IMAGE_RECITATION", "reprodução de conteúdo protegido",
            "BLOCKLIST", "termo bloqueado",
            "PROHIBITED_CONTENT", "conteúdo proibido",
            "IMAGE_PROHIBITED_CONTENT", "conteúdo proibido",
            "SPII", "dados pessoais sensíveis",
            "LANGUAGE", "idioma não suportado");

    private final GeminiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final int pedacoMaximo;

    @Autowired
    public ModeloGemini(GeminiProperties properties, ObjectMapper json) {
        this(properties, json, PEDACO_PADRAO);
    }

    /** Com pedaços menores no envio (os testes). */
    ModeloGemini(GeminiProperties properties, ObjectMapper json, int pedacoMaximo) {
        this.properties = properties;
        this.json = json;
        this.pedacoMaximo = pedacoMaximo;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.tempoConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ------------------------------------------------------------------------------------------------ arquivos

    @Override
    public ArquivoRemoto enviar(String chave, Path arquivo, String tipoDeMidia, long tamanho, String nomeParaExibir) {
        if (tamanho <= 0) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "O arquivo está vazio.");
        }
        HttpRequest inicio = HttpRequest.newBuilder(endereco("/upload/v1beta/files"))
                .header("x-goog-api-key", chave)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", Long.toString(tamanho))
                .header("X-Goog-Upload-Header-Content-Type", tipoDeMidia)
                .header("Content-Type", "application/json")
                .timeout(properties.tempoEnvio())
                .POST(HttpRequest.BodyPublishers.ofString(texto(Map.of("file", Map.of("display_name", nomeParaExibir)))))
                .build();
        HttpResponse<String> resposta = enviarPedido(inicio, "início do envio");
        if (resposta.statusCode() != 200) {
            throw falha(resposta.statusCode(), resposta.body(), false);
        }
        URI destino = resposta.headers().firstValue("x-goog-upload-url").map(URI::create)
                .orElseThrow(() -> new FalhaDoModelo(Tipo.INDISPONIVEL, "O Google não devolveu o endereço do envio."));
        long granularidade = resposta.headers().firstValue("x-goog-upload-chunk-granularity").map(Long::parseLong)
                .filter(g -> g > 0).orElse((long) pedacoMaximo);
        int pedaco = (int) Math.max(granularidade, pedacoMaximo / granularidade * granularidade);
        boolean mesmoHost = properties.urlBase().getHost().equalsIgnoreCase(destino.getHost());

        long enviado = 0;
        int quedas = 0;
        try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.READ)) {
            while (true) {
                int n = (int) Math.min(pedaco, tamanho - enviado);
                boolean ultimo = enviado + n >= tamanho;
                byte[] bytes = ler(canal, enviado, n);
                HttpRequest.Builder pedido = HttpRequest.newBuilder(destino)
                        .header("X-Goog-Upload-Command", ultimo ? "upload, finalize" : "upload")
                        .header("X-Goog-Upload-Offset", Long.toString(enviado))
                        .timeout(properties.tempoEnvio())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
                if (mesmoHost) {
                    pedido.header("x-goog-api-key", chave);
                }
                try {
                    HttpResponse<String> r = http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() >= 500) {
                        throw new IOException("HTTP " + r.statusCode());
                    }
                    if (r.statusCode() != 200) {
                        throw falha(r.statusCode(), r.body(), false);
                    }
                    enviado += n;
                    if (ultimo) {
                        ArquivoRemoto remoto = remoto(json.readTree(r.body()).path("file"), tipoDeMidia);
                        log.info("Arquivo enviado ao Gemini: {} bytes, {}, {} queda(s)", tamanho, tipoDeMidia, quedas);
                        return remoto;
                    }
                } catch (IOException e) {
                    if (++quedas > QUEDAS_NO_ENVIO) {
                        log.warn("Envio ao Gemini caiu {} vezes ({}): desistindo", quedas, e.getClass().getSimpleName());
                        throw new FalhaDoModelo(Tipo.INDISPONIVEL, "O envio do arquivo ao Gemini caiu e não voltou.");
                    }
                    Recebido recebido = recebido(destino, mesmoHost ? chave : null, tipoDeMidia);
                    if (recebido.arquivo() != null) {
                        return recebido.arquivo();
                    }
                    log.info("Envio ao Gemini caiu ({}): continuando de {} de {} bytes", e.getClass().getSimpleName(),
                            recebido.bytes(), tamanho);
                    enviado = recebido.bytes();
                }
            }
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "Não foi possível ler o arquivo para enviar ao Gemini.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "O envio foi interrompido.");
        }
    }

    /** Quanto o Google já recebeu (ou o arquivo pronto, se o envio terminou do lado dele). */
    private Recebido recebido(URI destino, String chave, String tipoDeMidia) throws InterruptedException {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(destino)
                .header("X-Goog-Upload-Command", "query")
                .timeout(properties.tempoEnvio())
                .POST(HttpRequest.BodyPublishers.noBody());
        if (chave != null) {
            pedido.header("x-goog-api-key", chave);
        }
        try {
            HttpResponse<String> r = http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw falha(r.statusCode(), r.body(), false);
            }
            String situacao = r.headers().firstValue("x-goog-upload-status").orElse("active");
            if ("final".equalsIgnoreCase(situacao) && r.body() != null && !r.body().isBlank()) {
                return new Recebido(0, remoto(json.readTree(r.body()).path("file"), tipoDeMidia));
            }
            long bytes = r.headers().firstValue("x-goog-upload-size-received").map(Long::parseLong).orElse(0L);
            return new Recebido(bytes, null);
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "O envio do arquivo ao Gemini caiu e não voltou.");
        }
    }

    private record Recebido(long bytes, ArquivoRemoto arquivo) {
    }

    @Override
    public ArquivoRemoto consultar(String chave, String nome) {
        HttpRequest pedido = HttpRequest.newBuilder(endereco("/v1beta/" + nomeDoArquivo(nome)))
                .header("x-goog-api-key", chave)
                .timeout(properties.tempoEnvio())
                .GET()
                .build();
        HttpResponse<String> resposta = enviarPedido(pedido, "consulta do arquivo");
        if (resposta.statusCode() == 404) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "O arquivo não está mais no Gemini.", null, 404);
        }
        if (resposta.statusCode() != 200) {
            throw falha(resposta.statusCode(), resposta.body(), false);
        }
        try {
            return remoto(json.readTree(resposta.body()), null);
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "Resposta inesperada do Google ao consultar o arquivo.");
        }
    }

    @Override
    public void apagar(String chave, String nome) {
        HttpRequest pedido = HttpRequest.newBuilder(endereco("/v1beta/" + nomeDoArquivo(nome)))
                .header("x-goog-api-key", chave)
                .timeout(properties.tempoEnvio())
                .DELETE()
                .build();
        HttpResponse<String> resposta = enviarPedido(pedido, "apagar o arquivo");
        if (resposta.statusCode() != 200 && resposta.statusCode() != 204 && resposta.statusCode() != 404) {
            throw falha(resposta.statusCode(), resposta.body(), false);
        }
    }

    private ArquivoRemoto remoto(JsonNode arquivo, String tipoPadrao) {
        String nome = arquivo.path("name").asText("");
        if (!NOME_DO_ARQUIVO.matcher(nome).matches()) {
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "Resposta inesperada do Google ao receber o arquivo.");
        }
        ArquivoRemoto.Estado estado = switch (arquivo.path("state").asText("PROCESSING")) {
            case "ACTIVE" -> ArquivoRemoto.Estado.ATIVO;
            case "FAILED" -> ArquivoRemoto.Estado.FALHOU;
            default -> ArquivoRemoto.Estado.PROCESSANDO;
        };
        Instant expira = null;
        try {
            String quando = arquivo.path("expirationTime").asText("");
            expira = quando.isEmpty() ? null : Instant.parse(quando);
        } catch (DateTimeParseException e) {
            // sem a data, quem usa trata como "não sei quando expira"
        }
        String tipo = arquivo.path("mimeType").asText("");
        return new ArquivoRemoto(nome, arquivo.path("uri").asText(null), tipo.isEmpty() ? tipoPadrao : tipo, estado, expira);
    }

    // ------------------------------------------------------------------------------------------------ geração

    @Override
    public RespostaDoModelo gerar(String chave, PedidoAoModelo pedido) {
        if (!NOME_DO_MODELO.matcher(pedido.modelo()).matches()) {
            throw new FalhaDoModelo(Tipo.MODELO_INEXISTENTE, "O nome do modelo na configuração é inválido.");
        }
        String corpo;
        try {
            corpo = json.writeValueAsString(corpo(pedido));
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "O esquema da resposta é inválido.");
        }
        HttpRequest requisicao = HttpRequest.newBuilder(endereco("/v1beta/models/" + pedido.modelo() + ":generateContent"))
                .header("x-goog-api-key", chave)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(properties.tempoLimite())
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        long inicio = System.nanoTime();
        HttpResponse<String> resposta = enviarPedido(requisicao, "geração");
        if (resposta.statusCode() != 200) {
            FalhaDoModelo falha = falha(resposta.statusCode(), resposta.body(), true);
            log.info("Gemini {}: HTTP {} -> {}", pedido.modelo(), resposta.statusCode(), falha.tipo());
            throw falha;
        }
        RespostaDoModelo lida = ler(resposta.body());
        log.info("Gemini {}: {} tokens de entrada, {} de saída, {} ms", pedido.modelo(), lida.tokensEntrada(),
                lida.tokensSaida(), (System.nanoTime() - inicio) / 1_000_000);
        return lida;
    }

    private ObjectNode corpo(PedidoAoModelo pedido) throws IOException {
        ObjectNode raiz = json.createObjectNode();
        if (pedido.instrucaoDoSistema() != null && !pedido.instrucaoDoSistema().isBlank()) {
            raiz.putObject("systemInstruction").putArray("parts").addObject().put("text", pedido.instrucaoDoSistema());
        }
        ObjectNode conteudo = raiz.putArray("contents").addObject();
        conteudo.put("role", "user");
        ArrayNode partes = conteudo.putArray("parts");
        PedidoAoModelo.Midia midia = pedido.midia();
        if (midia != null) {
            if (midia.dados() != null) {
                partes.addObject().putObject("inlineData").put("mimeType", midia.tipoDeMidia())
                        .put("data", Base64.getEncoder().encodeToString(midia.dados()));
            } else {
                ObjectNode parte = partes.addObject();
                parte.putObject("fileData").put("mimeType", midia.tipoDeMidia()).put("fileUri", midia.uri());
                if (midia.quadrosPorSegundo() != null) {
                    parte.putObject("videoMetadata").put("fps", midia.quadrosPorSegundo());
                }
            }
        }
        partes.addObject().put("text", pedido.instrucao());
        ObjectNode geracao = raiz.putObject("generationConfig");
        geracao.put("responseMimeType", "application/json");
        geracao.set("responseJsonSchema", json.readTree(pedido.esquemaJson()));
        if (pedido.maxTokensSaida() > 0) {
            geracao.put("maxOutputTokens", pedido.maxTokensSaida());
        }
        if (pedido.altaResolucao()) {
            geracao.put("mediaResolution", "MEDIA_RESOLUTION_HIGH");
        }
        if (pedido.temperatura() != null) {
            geracao.put("temperature", pedido.temperatura());
        }
        if (pedido.raciocinio() != null && !pedido.raciocinio().isBlank()) {
            geracao.putObject("thinkingConfig").put("thinkingLevel", pedido.raciocinio().strip());
        }
        return raiz;
    }

    /** O texto da resposta (sem as partes de raciocínio) e os tokens; bloqueio e resposta cortada viram falha. */
    RespostaDoModelo ler(String corpo) {
        JsonNode raiz;
        try {
            raiz = json.readTree(corpo);
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "Resposta inválida do Gemini.");
        }
        String bloqueio = raiz.path("promptFeedback").path("blockReason").asText("");
        if (!bloqueio.isEmpty()) {
            throw new FalhaDoModelo(Tipo.BLOQUEOU, motivo(bloqueio));
        }
        JsonNode candidato = raiz.path("candidates").path(0);
        if (candidato.isMissingNode()) {
            throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "O Gemini não devolveu resposta.");
        }
        String fim = candidato.path("finishReason").asText("");
        if (MOTIVOS_DE_BLOQUEIO.contains(fim)) {
            throw new FalhaDoModelo(Tipo.BLOQUEOU, motivo(fim));
        }
        if ("MAX_TOKENS".equals(fim)) {
            throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "A resposta do Gemini veio cortada (passou do tamanho máximo).");
        }
        StringBuilder texto = new StringBuilder();
        for (JsonNode parte : candidato.path("content").path("parts")) {
            if (!parte.path("thought").asBoolean(false) && parte.has("text")) {
                texto.append(parte.path("text").asText());
            }
        }
        if (texto.toString().isBlank()) {
            throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "O Gemini devolveu uma resposta vazia.");
        }
        JsonNode uso = raiz.path("usageMetadata");
        int entrada = uso.path("promptTokenCount").asInt(0);
        int saida = uso.path("candidatesTokenCount").asInt(0) + uso.path("thoughtsTokenCount").asInt(0);
        return new RespostaDoModelo(texto.toString(), entrada, saida);
    }

    private static String motivo(String codigo) {
        return MOTIVOS.getOrDefault(codigo, codigo.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    // ------------------------------------------------------------------------------------------------ erros

    /**
     * Traduz o erro do Google (error.code, error.status, details[].reason, a QuotaFailure e o RetryInfo).
     *
     * @param naGeracao um 404 na geração é o modelo que não existe; nos arquivos, o arquivo que sumiu
     */
    FalhaDoModelo falha(int status, String corpo, boolean naGeracao) {
        Erro erro = Erro.de(json, corpo);
        if (status == 429) {
            if (erro.cotaDiaria()) {
                return new FalhaDoModelo(Tipo.COTA_DIARIA, "A cota diária da sua chave do Gemini acabou.", null, status);
            }
            return new FalhaDoModelo(Tipo.LIMITE, "Aguardando o limite do Gemini.", erro.esperar(), status);
        }
        if (status == 402) {
            return new FalhaDoModelo(Tipo.SEM_CREDITO, "Os créditos da sua chave do Gemini acabaram.", null, status);
        }
        if (status == 401 || status == 403 || erro.tem("API_KEY_INVALID") || erro.tem("API_KEY_EXPIRED")
                || erro.tem("ACCESS_TOKEN_TYPE_UNSUPPORTED")) {
            String detalhe = erro.tem("SERVICE_DISABLED") ? " (a API do Gemini não está ativada no projeto da chave)"
                    : erro.tem("ACCESS_TOKEN_TYPE_UNSUPPORTED") ? " (o Google não aceitou esse tipo de chave nesta API)" : "";
            return new FalhaDoModelo(Tipo.CHAVE_RECUSADA, "Sua chave do Gemini foi recusada" + detalhe + ".", null, status);
        }
        if (status == 404) {
            return naGeracao
                    ? new FalhaDoModelo(Tipo.MODELO_INEXISTENTE, "O modelo da configuração não existe ou não está liberado "
                    + "para a sua chave.", null, status)
                    : new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "O arquivo não está mais no Gemini.", null, status);
        }
        if (status == 408 || status >= 500) {
            return new FalhaDoModelo(Tipo.INDISPONIVEL, "Gemini indisponível (HTTP " + status + ").", null, status);
        }
        String google = erro.mensagem().isBlank() ? "" : " (Google: " + cortar(erro.mensagem(), 200) + ")";
        return new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "O Gemini não aceitou o pedido" + google + ".", null, status);
    }

    /** O erro no formato das APIs do Google. */
    private record Erro(String status, List<String> motivos, List<String> cotas, String mensagem, Duration esperar) {

        static Erro de(ObjectMapper json, String corpo) {
            List<String> motivos = new ArrayList<>();
            List<String> cotas = new ArrayList<>();
            Duration esperar = null;
            String status = "";
            String mensagem = "";
            try {
                JsonNode erro = corpo == null || corpo.isBlank() ? null : json.readTree(corpo).path("error");
                if (erro != null && !erro.isMissingNode()) {
                    status = erro.path("status").asText("");
                    mensagem = erro.path("message").asText("");
                    for (JsonNode detalhe : erro.path("details")) {
                        if (detalhe.has("reason")) {
                            motivos.add(detalhe.path("reason").asText());
                        }
                        for (JsonNode violacao : detalhe.path("violations")) {
                            cotas.add(violacao.path("quotaId").asText("") + " " + violacao.path("quotaMetric").asText(""));
                        }
                        if (detalhe.has("retryDelay")) {
                            esperar = duracao(detalhe.path("retryDelay").asText(""));
                        }
                    }
                }
            } catch (IOException e) {
                // corpo que não é JSON: fica só o código HTTP
            }
            return new Erro(status, motivos, cotas, mensagem, esperar);
        }

        boolean tem(String motivo) {
            return motivos.contains(motivo) || status.equals(motivo);
        }

        /** Cota por dia ("...PerDay...") ou a da conta inteira acabou. */
        boolean cotaDiaria() {
            return cotas.stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains("perday")
                    || c.toLowerCase(Locale.ROOT).contains("per_day"));
        }

        private static Duration duracao(String texto) {
            try {
                String numero = texto.endsWith("s") ? texto.substring(0, texto.length() - 1) : texto;
                return Duration.ofMillis(Math.round(Double.parseDouble(numero) * 1000));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ apoio

    private HttpResponse<String> enviarPedido(HttpRequest pedido, String oQue) {
        try {
            return http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log.warn("Gemini ({}): o Google não respondeu a tempo", oQue);
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "O Gemini não respondeu a tempo.");
        } catch (ConnectException | UnknownHostException e) {
            log.warn("Gemini ({}): sem conexão com o Google ({})", oQue, e.getClass().getSimpleName());
            throw new FalhaDoModelo(Tipo.SEM_INTERNET, "Sem conexão com o Google.");
        } catch (IOException e) {
            log.warn("Gemini ({}): a conexão caiu ({})", oQue, e.getClass().getSimpleName());
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "A conexão com o Gemini caiu.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "A chamada ao Gemini foi interrompida.");
        }
    }

    private static byte[] ler(FileChannel canal, long posicao, int n) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(n);
        while (buffer.hasRemaining()) {
            if (canal.read(buffer, posicao + buffer.position()) < 0) {
                throw new IOException("O arquivo diminuiu durante o envio");
            }
        }
        return buffer.array();
    }

    private String texto(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nomeDoArquivo(String nome) {
        if (nome == null || !NOME_DO_ARQUIVO.matcher(nome).matches()) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "Arquivo do Gemini com nome inesperado.");
        }
        return nome;
    }

    private URI endereco(String caminho) {
        String base = properties.urlBase().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + caminho);
    }

    private static String cortar(String texto, int maximo) {
        return texto.length() <= maximo ? texto : texto.substring(0, maximo) + "…";
    }
}
