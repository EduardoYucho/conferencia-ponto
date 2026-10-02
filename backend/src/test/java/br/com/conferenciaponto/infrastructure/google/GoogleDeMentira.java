package br.com.conferenciaponto.infrastructure.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Um "Google" local para os testes: o serviço de tokens e o pedaço da API do Sheets que o sistema usa, com as
 * planilhas em memória. Recusa o que o Google de verdade recusaria (token errado, aba repetida, apagar a
 * última aba) para os testes pegarem requisições fora de ordem.
 */
public class GoogleDeMentira implements AutoCloseable {

    /** Aba de uma planilha em memória: o que foi gravado em cada célula ("A1" → valor digitado). */
    public static final class AbaFalsa {
        public final int id;
        public String titulo;
        public int linhas = 1000;
        public int colunas = 26;
        public int congeladas;
        public boolean protegida;
        public final Map<String, JsonNode> celulas = new TreeMap<>();

        AbaFalsa(int id, String titulo) {
            this.id = id;
            this.titulo = titulo;
        }

        /** Valor digitado: texto, número ou fórmula (com o "="). */
        public Object valor(String ref) {
            JsonNode v = celulas.get(ref) == null ? null : celulas.get(ref).path("userEnteredValue");
            if (v == null || v.isMissingNode()) {
                return null;
            }
            if (v.has("stringValue")) {
                return v.get("stringValue").asText();
            }
            if (v.has("formulaValue")) {
                return v.get("formulaValue").asText();
            }
            return v.get("numberValue").asDouble();
        }

        public JsonNode formato(String ref) {
            return celulas.get(ref) == null ? null : celulas.get(ref).path("userEnteredFormat");
        }
    }

    public static final class PlanilhaFalsa {
        public final String titulo;
        public final List<AbaFalsa> abas = new ArrayList<>();

        PlanilhaFalsa(String titulo) {
            this.titulo = titulo;
        }

        public AbaFalsa aba(int id) {
            return abas.stream().filter(a -> a.id == id).findFirst().orElse(null);
        }

        public List<String> titulos() {
            return abas.stream().map(a -> a.titulo).toList();
        }
    }

    /** Resposta de erro programada para a próxima chamada à API (não ao serviço de tokens). */
    private record Falha(int status, String corpo) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer servidor;
    private final KeyPair chaves;
    private final Map<String, PlanilhaFalsa> planilhas = new LinkedHashMap<>();
    private final Deque<Falha> falhas = new ArrayDeque<>();
    private final List<JsonNode> requisicoes = new ArrayList<>();
    private int tokens;
    private int chamadas;
    private String tokenValido;
    private JsonNode ultimaAssercao;
    private boolean recusarChave;

    public GoogleDeMentira() throws Exception {
        this(0);
    }

    public GoogleDeMentira(int porta) throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        chaves = gerador.generateKeyPair();
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", porta), 0);
        servidor.createContext("/token", this::token);
        servidor.createContext("/v4/spreadsheets/", this::api);
        servidor.start();
    }

    public String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    /** O arquivo .json que o Google Cloud entregaria para a conta de serviço deste "Google". */
    public String chaveJson() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(chaves.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        ObjectNode no = json.createObjectNode();
        no.put("type", "service_account");
        no.put("project_id", "projeto-de-teste");
        no.put("private_key_id", "chave-1");
        no.put("private_key", pem);
        no.put("client_email", "planilhas@projeto-de-teste.iam.gserviceaccount.com");
        no.put("token_uri", "https://oauth2.googleapis.com/token");
        return no.toPrettyString();
    }

    /** Planilha nova, como a do sheets.new: só a aba vazia "Página1". */
    public synchronized PlanilhaFalsa criarPlanilha(String id, String titulo) {
        PlanilhaFalsa p = new PlanilhaFalsa(titulo);
        p.abas.add(new AbaFalsa(0, "Página1"));
        planilhas.put(id, p);
        return p;
    }

    public synchronized PlanilhaFalsa planilha(String id) {
        return planilhas.get(id);
    }

    /** A próxima chamada à API responde com este erro, no formato do Google. */
    public synchronized void falharCom(int status, String estado, String mensagem, String motivo) {
        ObjectNode erro = json.createObjectNode();
        erro.put("code", status);
        erro.put("message", mensagem);
        erro.put("status", estado);
        if (motivo != null) {
            erro.putArray("details").addObject().put("@type", "type.googleapis.com/google.rpc.ErrorInfo").put("reason", motivo);
        }
        ObjectNode corpo = json.createObjectNode();
        corpo.set("error", erro);
        falhas.add(new Falha(status, corpo.toString()));
    }

    /** O token entregue deixa de valer (o próximo uso recebe 401). */
    public synchronized void invalidarToken() {
        tokenValido = "outro-token";
    }

    public synchronized void recusarChave(boolean recusar) {
        recusarChave = recusar;
    }

    public synchronized int tokensEmitidos() {
        return tokens;
    }

    /** Chamadas à API (sem contar o serviço de tokens). */
    public synchronized int chamadas() {
        return chamadas;
    }

    /** Declarações (claims) do último JWT recebido pelo serviço de tokens. */
    public synchronized JsonNode ultimaAssercao() {
        return ultimaAssercao;
    }

    /** Todas as requisições de batchUpdate recebidas, em ordem (cada uma {tipo: {...}}). */
    public synchronized List<JsonNode> requisicoes() {
        return List.copyOf(requisicoes);
    }

    public synchronized List<String> tipos() {
        return requisicoes.stream().map(r -> r.fieldNames().next()).toList();
    }

    public synchronized void esquecerRequisicoes() {
        requisicoes.clear();
        chamadas = 0;
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    // ------------------------------------------------------------------ serviço de tokens

    private void token(HttpExchange troca) throws IOException {
        try {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> campos = new LinkedHashMap<>();
            for (String par : corpo.split("&")) {
                String[] kv = par.split("=", 2);
                campos.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            String[] jwt = campos.getOrDefault("assertion", "").split("\\.");
            boolean assinado = jwt.length == 3 && "urn:ietf:params:oauth:grant-type:jwt-bearer".equals(campos.get("grant_type"))
                    && assinaturaValida(jwt, chaves.getPublic());
            synchronized (this) {
                if (!assinado || recusarChave) {
                    responder(troca, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}");
                    return;
                }
                ultimaAssercao = json.readTree(Base64.getUrlDecoder().decode(jwt[1]));
                tokenValido = "token-" + (++tokens);
                responder(troca, 200, "{\"access_token\":\"" + tokenValido + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
            }
        } catch (Exception e) {
            responder(troca, 500, "{\"error\":\"" + e + "\"}");
        }
    }

    private static boolean assinaturaValida(String[] jwt, PublicKey chave) throws Exception {
        Signature assinatura = Signature.getInstance("SHA256withRSA");
        assinatura.initVerify(chave);
        assinatura.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        return assinatura.verify(Base64.getUrlDecoder().decode(jwt[2]));
    }

    // ------------------------------------------------------------------ API do Sheets

    private void api(HttpExchange troca) throws IOException {
        try {
            synchronized (this) {
                chamadas++;
                String autorizacao = troca.getRequestHeaders().getFirst("Authorization");
                if (tokenValido == null || !("Bearer " + tokenValido).equals(autorizacao)) {
                    responder(troca, 401, erro(401, "UNAUTHENTICATED", "Request had invalid authentication credentials."));
                    return;
                }
                if (!falhas.isEmpty()) {
                    Falha falha = falhas.poll();
                    responder(troca, falha.status(), falha.corpo());
                    return;
                }
                String caminho = troca.getRequestURI().getRawPath().substring("/v4/spreadsheets/".length());
                String metodo = troca.getRequestMethod();
                if (metodo.equals("POST") && caminho.endsWith(":batchUpdate")) {
                    atualizar(troca, caminho.substring(0, caminho.length() - ":batchUpdate".length()));
                } else if (metodo.equals("GET") && caminho.contains("/values/")) {
                    String[] partes = caminho.split("/values/", 2);
                    valores(troca, partes[0], URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
                } else if (metodo.equals("GET")) {
                    metadados(troca, caminho);
                } else {
                    responder(troca, 404, erro(404, "NOT_FOUND", "Rota desconhecida"));
                }
            }
        } catch (Exception e) {
            responder(troca, 500, erro(500, "INTERNAL", String.valueOf(e)));
        }
    }

    private void metadados(HttpExchange troca, String id) throws IOException {
        PlanilhaFalsa p = planilhas.get(id);
        if (p == null) {
            responder(troca, 404, erro(404, "NOT_FOUND", "Requested entity was not found."));
            return;
        }
        ObjectNode no = json.createObjectNode();
        no.putObject("properties").put("title", p.titulo);
        ArrayNode abas = no.putArray("sheets");
        for (int i = 0; i < p.abas.size(); i++) {
            AbaFalsa a = p.abas.get(i);
            abas.addObject().putObject("properties").put("sheetId", a.id).put("title", a.titulo).put("index", i);
        }
        responder(troca, 200, no.toString());
    }

    private void valores(HttpExchange troca, String id, String intervalo) throws IOException {
        PlanilhaFalsa p = planilhas.get(id);
        String titulo = intervalo.replaceAll("^'|'$", "").replace("''", "'");
        AbaFalsa aba = p == null ? null : p.abas.stream().filter(a -> a.titulo.equals(titulo)).findFirst().orElse(null);
        if (aba == null) {
            responder(troca, 400, erro(400, "INVALID_ARGUMENT", "Unable to parse range: " + intervalo));
            return;
        }
        ObjectNode no = json.createObjectNode();
        no.put("range", intervalo).put("majorDimension", "ROWS");
        boolean temValor = aba.celulas.values().stream().anyMatch(c -> c.has("userEnteredValue"));
        if (temValor) {
            no.putArray("values").addArray().add("...");
        }
        responder(troca, 200, no.toString());
    }

    private void atualizar(HttpExchange troca, String id) throws IOException {
        PlanilhaFalsa p = planilhas.get(id);
        if (p == null) {
            responder(troca, 404, erro(404, "NOT_FOUND", "Requested entity was not found."));
            return;
        }
        JsonNode corpo = json.readTree(troca.getRequestBody().readAllBytes());
        // como no Google: ou todas as requisições valem, ou nenhuma
        List<AbaFalsa> copia = new ArrayList<>();
        for (AbaFalsa a : p.abas) {
            AbaFalsa c = new AbaFalsa(a.id, a.titulo);
            c.linhas = a.linhas;
            c.colunas = a.colunas;
            c.congeladas = a.congeladas;
            c.protegida = a.protegida;
            c.celulas.putAll(a.celulas);
            copia.add(c);
        }
        List<JsonNode> recebidas = new ArrayList<>();
        for (JsonNode requisicao : corpo.path("requests")) {
            String recusa = aplicar(copia, requisicao);
            if (recusa != null) {
                responder(troca, 400, erro(400, "INVALID_ARGUMENT", "Invalid requests[" + recebidas.size() + "]: " + recusa));
                return;
            }
            recebidas.add(requisicao);
        }
        p.abas.clear();
        p.abas.addAll(copia);
        requisicoes.addAll(recebidas);
        responder(troca, 200, "{\"spreadsheetId\":\"" + id + "\",\"replies\":[]}");
    }

    /** @return o motivo da recusa, ou {@code null} se a requisição foi aplicada */
    private String aplicar(List<AbaFalsa> abas, JsonNode requisicao) {
        String tipo = requisicao.fieldNames().next();
        JsonNode r = requisicao.get(tipo);
        switch (tipo) {
            case "addSheet" -> {
                JsonNode props = r.path("properties");
                int id = props.path("sheetId").asInt();
                String titulo = props.path("title").asText();
                if (abas.stream().anyMatch(a -> a.id == id)) {
                    return "addSheet: A sheet with the ID " + id + " already exists.";
                }
                if (abas.stream().anyMatch(a -> a.titulo.equalsIgnoreCase(titulo))) {
                    return "addSheet: A sheet with the name \"" + titulo + "\" already exists.";
                }
                AbaFalsa nova = new AbaFalsa(id, titulo);
                grade(nova, props.path("gridProperties"));
                abas.add(Math.min(props.path("index").asInt(abas.size()), abas.size()), nova);
            }
            case "updateSheetProperties" -> {
                JsonNode props = r.path("properties");
                AbaFalsa aba = aba(abas, props.path("sheetId").asInt());
                if (aba == null) {
                    return "updateSheetProperties: No sheet with id " + props.path("sheetId").asInt();
                }
                String titulo = props.path("title").asText(aba.titulo);
                if (abas.stream().anyMatch(a -> a != aba && a.titulo.equalsIgnoreCase(titulo))) {
                    return "updateSheetProperties: A sheet with the name \"" + titulo + "\" already exists.";
                }
                aba.titulo = titulo;
                String recusa = grade(aba, props.path("gridProperties"));
                if (recusa != null) {
                    return recusa;
                }
            }
            case "updateCells" -> {
                AbaFalsa aba = aba(abas, r.path("range").path("sheetId").asInt());
                if (aba == null) {
                    return "updateCells: No grid with id " + r.path("range").path("sheetId").asInt();
                }
                if (r.path("rows").size() > aba.linhas) {
                    return "updateCells: Attempting to write row " + r.path("rows").size() + ", beyond the last row " + aba.linhas;
                }
                aba.celulas.clear();
                int linha = 0;
                for (JsonNode valores : r.path("rows")) {
                    if (valores.path("values").size() > aba.colunas) {
                        return "updateCells: Attempting to write column beyond the last column " + aba.colunas;
                    }
                    int coluna = 0;
                    for (JsonNode celula : valores.path("values")) {
                        if (celula.size() > 0) {
                            aba.celulas.put(ref(linha, coluna), celula);
                        }
                        coluna++;
                    }
                    linha++;
                }
            }
            case "deleteSheet" -> {
                AbaFalsa aba = aba(abas, r.path("sheetId").asInt());
                if (aba == null) {
                    return "deleteSheet: No sheet with id " + r.path("sheetId").asInt();
                }
                if (abas.size() == 1) {
                    return "deleteSheet: You can't remove all the sheets in a document.";
                }
                abas.remove(aba);
            }
            case "addProtectedRange" -> {
                AbaFalsa aba = aba(abas, r.path("protectedRange").path("range").path("sheetId").asInt());
                if (aba == null) {
                    return "addProtectedRange: No grid with that id";
                }
                aba.protegida = true;
            }
            case "repeatCell", "updateDimensionProperties" -> {
                int id = r.path("range").path("sheetId").asInt();
                if (aba(abas, id) == null) {
                    return tipo + ": No grid with id " + id;
                }
            }
            default -> {
                return "Unknown request " + tipo;
            }
        }
        return null;
    }

    private static String grade(AbaFalsa aba, JsonNode grade) {
        aba.linhas = grade.path("rowCount").asInt(aba.linhas);
        aba.colunas = grade.path("columnCount").asInt(aba.colunas);
        aba.congeladas = grade.path("frozenRowCount").asInt(aba.congeladas);
        return aba.congeladas >= aba.linhas ? "You can't freeze all visible rows on the sheet." : null;
    }

    private static AbaFalsa aba(List<AbaFalsa> abas, int id) {
        return abas.stream().filter(a -> a.id == id).findFirst().orElse(null);
    }

    private static String ref(int linha, int coluna) {
        StringBuilder letras = new StringBuilder();
        for (int c = coluna; c >= 0; c = c / 26 - 1) {
            letras.insert(0, (char) ('A' + c % 26));
        }
        return letras.toString() + (linha + 1);
    }

    private String erro(int status, String estado, String mensagem) {
        ObjectNode corpo = json.createObjectNode();
        corpo.putObject("error").put("code", status).put("message", mensagem).put("status", estado);
        return corpo.toString();
    }

    private static void responder(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        troca.sendResponseHeaders(status, bytes.length);
        troca.getResponseBody().write(bytes);
        troca.close();
    }

    /**
     * Sobe o "Google" numa porta fixa, para testar o sistema inteiro sem internet: grava a chave da conta de
     * serviço no arquivo informado e cria as planilhas pedidas.
     * Uso: {@code GoogleDeMentira <porta> <arquivo-da-chave> [idDaPlanilha ...]}
     */
    public static void main(String[] args) throws Exception {
        GoogleDeMentira google = new GoogleDeMentira(Integer.parseInt(args[0]));
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), google.chaveJson());
        for (int i = 2; i < args.length; i++) {
            google.criarPlanilha(args[i], "Planilha " + args[i]);
        }
        google.servidor.createContext("/estado", troca -> {
            synchronized (google) {
                ObjectNode no = google.json.createObjectNode();
                no.put("chamadas", google.chamadas).put("tokens", google.tokens);
                ArrayNode tipos = no.putArray("tipos");
                google.tipos().forEach(tipos::add);
                ObjectNode planilhas = no.putObject("planilhas");
                google.planilhas.forEach((id, p) -> {
                    ArrayNode abas = planilhas.putArray(id);
                    for (AbaFalsa a : p.abas) {
                        ObjectNode aba = abas.addObject();
                        aba.put("id", a.id).put("titulo", a.titulo).put("linhas", a.linhas).put("colunas", a.colunas)
                                .put("congeladas", a.congeladas);
                        ObjectNode celulas = aba.putObject("celulas");
                        a.celulas.forEach((ref, c) -> celulas.set(ref, c.path("userEnteredValue")));
                    }
                });
                responder(troca, 200, no.toString());
            }
        });
        System.out.println("Google de mentira em " + google.url());
        Thread.currentThread().join();
    }
}
