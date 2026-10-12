package br.com.conferenciaponto.modulos.atendimento;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uma API do Gemini de mentira (HTTP local): generateContent com respostas na fila (ou a padrão), e a Files API com
 * o envio retomável ("start", pedaços com "upload", "query" e "upload, finalize"), a consulta da situação e o
 * apagar. Guarda os pedidos recebidos (cabeçalhos e corpo), para conferir onde a chave foi e o que saiu.
 */
public final class ServidorDoGemini implements AutoCloseable {

    /** Um pedido recebido; os cabeçalhos com o nome em minúsculas. */
    public record Pedido(String metodo, String caminho, String consulta, Map<String, String> cabecalhos, byte[] corpo) {

        public String texto() {
            return new String(corpo, StandardCharsets.UTF_8);
        }
    }

    private record Resposta(int status, String corpo, long atrasoMs) {
    }

    private final HttpServer servidor;
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    private final Deque<Resposta> respostas = new ArrayDeque<>();
    private volatile String padrao = sucesso("{}", 10, 5);
    private final AtomicInteger sequencia = new AtomicInteger();
    private final Map<String, Envio> envios = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> estados = new ConcurrentHashMap<>();
    private final Map<String, byte[]> arquivos = new ConcurrentHashMap<>();
    private final Map<String, String> tipos = new ConcurrentHashMap<>();
    private final List<String> apagados = new CopyOnWriteArrayList<>();
    private volatile String estadoInicial = "ACTIVE";
    private volatile List<String> estadosDepois = List.of();
    private volatile long granularidade = 0;
    private volatile int cairNoPedaco = -1;
    private volatile boolean uploadEmOutroHost;

    private static final class Envio {
        final ByteArrayOutputStream recebido = new ByteArrayOutputStream();
        final String tipo;
        int pedacos;

        Envio(String tipo) {
            this.tipo = tipo;
        }
    }

    public ServidorDoGemini() {
        try {
            servidor = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        servidor.createContext("/", this::responder);
        servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        servidor.start();
    }

    /** Endereço da API (com "localhost", para o envio poder ir a "outro host": 127.0.0.1). */
    public URI base() {
        return URI.create("http://localhost:" + servidor.getAddress().getPort());
    }

    // ------------------------------------------------------------------------------------------- respostas

    /** O corpo de uma geração que deu certo, com o texto (o JSON pedido) e os tokens. */
    public static String sucesso(String texto, int entrada, int saida) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + aspas(texto) + "}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":" + entrada
                + ",\"candidatesTokenCount\":" + saida + ",\"totalTokenCount\":" + (entrada + saida) + "}}";
    }

    /** O corpo de um erro no formato das APIs do Google. */
    public static String erro(int codigo, String status, String mensagem, String detalhes) {
        return "{\"error\":{\"code\":" + codigo + ",\"message\":" + aspas(mensagem) + ",\"status\":\"" + status + "\""
                + (detalhes == null ? "" : ",\"details\":" + detalhes) + "}}";
    }

    /** A próxima geração responde isto (as seguintes seguem a fila; vazia, a padrão). */
    public synchronized ServidorDoGemini responder(int status, String corpo) {
        respostas.addLast(new Resposta(status, corpo, 0));
        return this;
    }

    public synchronized ServidorDoGemini responderDevagar(int status, String corpo, long atrasoMs) {
        respostas.addLast(new Resposta(status, corpo, atrasoMs));
        return this;
    }

    public ServidorDoGemini padrao(String corpo) {
        this.padrao = corpo;
        return this;
    }

    /** O arquivo enviado nasce nesta situação e passa pelas seguintes, uma a cada consulta. */
    public ServidorDoGemini estados(String inicial, String... depois) {
        this.estadoInicial = inicial;
        this.estadosDepois = List.of(depois);
        return this;
    }

    public ServidorDoGemini granularidade(long bytes) {
        this.granularidade = bytes;
        return this;
    }

    /** A conexão cai no meio deste pedaço do envio (o primeiro é o 1), depois de receber metade dele. */
    public ServidorDoGemini cairNoPedaco(int pedaco) {
        this.cairNoPedaco = pedaco;
        return this;
    }

    public ServidorDoGemini uploadEmOutroHost() {
        this.uploadEmOutroHost = true;
        return this;
    }

    // ------------------------------------------------------------------------------------------- consulta

    public List<Pedido> pedidos() {
        return List.copyOf(pedidos);
    }

    public List<Pedido> geracoes() {
        return pedidos.stream().filter(p -> p.caminho().endsWith(":generateContent")).toList();
    }

    public byte[] arquivoRecebido(String nome) {
        return arquivos.get(nome);
    }

    public List<String> apagados() {
        return List.copyOf(apagados);
    }

    // ------------------------------------------------------------------------------------------- servidor

    private void responder(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        Map<String, String> cabecalhos = new HashMap<>();
        troca.getRequestHeaders().forEach((nome, valores) -> cabecalhos.put(nome.toLowerCase(Locale.ROOT), String.join(",", valores)));
        String comando = cabecalhos.getOrDefault("x-goog-upload-command", "");
        try (troca) {
            if (caminho.startsWith("/upload-sessao/") && comando.startsWith("upload")) {
                pedaco(troca, caminho.substring("/upload-sessao/".length()), cabecalhos, comando);
                return;
            }
            byte[] corpo = ler(troca.getRequestBody());
            pedidos.add(new Pedido(troca.getRequestMethod(), caminho, troca.getRequestURI().getQuery(), cabecalhos, corpo));
            if (caminho.endsWith(":generateContent") || caminho.endsWith(":embedContent")) {
                Resposta r;
                synchronized (this) {
                    r = respostas.isEmpty() ? new Resposta(200, padrao, 0) : respostas.removeFirst();
                }
                if (r.atrasoMs() > 0) {
                    Thread.sleep(r.atrasoMs());
                }
                enviar(troca, r.status(), r.corpo());
            } else if (caminho.equals("/upload/v1beta/files")) {
                String id = "s" + sequencia.incrementAndGet();
                envios.put(id, new Envio(cabecalhos.getOrDefault("x-goog-upload-header-content-type", "application/octet-stream")));
                String host = uploadEmOutroHost ? "127.0.0.1" : "localhost";
                troca.getResponseHeaders().add("x-goog-upload-url",
                        "http://" + host + ":" + servidor.getAddress().getPort() + "/upload-sessao/" + id);
                troca.getResponseHeaders().add("x-goog-upload-status", "active");
                if (granularidade > 0) {
                    troca.getResponseHeaders().add("x-goog-upload-chunk-granularity", Long.toString(granularidade));
                }
                enviar(troca, 200, "");
            } else if (caminho.startsWith("/upload-sessao/") && comando.equals("query")) {
                Envio envio = envios.get(caminho.substring("/upload-sessao/".length()));
                troca.getResponseHeaders().add("x-goog-upload-status", "active");
                troca.getResponseHeaders().add("x-goog-upload-size-received", Integer.toString(envio.recebido.size()));
                enviar(troca, 200, "");
            } else if (caminho.startsWith("/v1beta/files/") && troca.getRequestMethod().equals("GET")) {
                String nome = caminho.substring("/v1beta/".length());
                Deque<String> fila = estados.get(nome);
                if (fila == null || apagados.contains(nome)) {
                    enviar(troca, 404, erro(404, "NOT_FOUND", "File not found", null));
                    return;
                }
                String estado = fila.size() > 1 ? fila.removeFirst() : fila.peekFirst();
                enviar(troca, 200, arquivo(nome, tipos.get(nome), estado));
            } else if (caminho.startsWith("/v1beta/files/") && troca.getRequestMethod().equals("DELETE")) {
                apagados.add(caminho.substring("/v1beta/".length()));
                enviar(troca, 200, "{}");
            } else {
                enviar(troca, 404, erro(404, "NOT_FOUND", "Not found", null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pedaco(HttpExchange troca, String id, Map<String, String> cabecalhos, String comando) throws IOException {
        Envio envio = envios.get(id);
        long deslocamento = Long.parseLong(cabecalhos.getOrDefault("x-goog-upload-offset", "-1"));
        int vez;
        synchronized (envio) {
            vez = ++envio.pedacos;
        }
        InputStream entrada = troca.getRequestBody();
        if (vez == cairNoPedaco) {
            byte[] tudo = ler(entrada);
            synchronized (envio) {
                envio.recebido.write(tudo, 0, tudo.length / 2);
            }
            pedidos.add(new Pedido("POST", "/upload-sessao/" + id, null, cabecalhos, new byte[0]));
            troca.getResponseBody().close(); // sem resposta: a conexão "cai"
            throw new IOException("queda simulada");
        }
        byte[] corpo = ler(entrada);
        pedidos.add(new Pedido("POST", "/upload-sessao/" + id, null, cabecalhos, new byte[0]));
        synchronized (envio) {
            if (deslocamento != envio.recebido.size()) {
                enviar(troca, 400, erro(400, "INVALID_ARGUMENT", "Offset errado: " + deslocamento, null));
                return;
            }
            envio.recebido.write(corpo, 0, corpo.length);
        }
        if (comando.contains("finalize")) {
            String nome = "files/" + id;
            arquivos.put(nome, envio.recebido.toByteArray());
            tipos.put(nome, envio.tipo);
            Deque<String> fila = new ArrayDeque<>();
            fila.add(estadoInicial);
            fila.addAll(estadosDepois);
            estados.put(nome, fila);
            troca.getResponseHeaders().add("x-goog-upload-status", "final");
            enviar(troca, 200, "{\"file\":" + arquivo(nome, envio.tipo, estadoInicial) + "}");
        } else {
            troca.getResponseHeaders().add("x-goog-upload-status", "active");
            enviar(troca, 200, "");
        }
    }

    private String arquivo(String nome, String tipo, String estado) {
        return "{\"name\":\"" + nome + "\",\"mimeType\":\"" + tipo + "\",\"uri\":\"" + base() + "/v1beta/" + nome
                + "\",\"state\":\"" + estado + "\",\"expirationTime\":\"2099-01-01T00:00:00Z\"}";
    }

    private static void enviar(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(bytes);
            }
        }
    }

    private static byte[] ler(InputStream entrada) throws IOException {
        return entrada.readAllBytes();
    }

    private static String aspas(String texto) {
        StringBuilder s = new StringBuilder("\"");
        for (char c : texto.toCharArray()) {
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20) {
                        s.append(String.format("\\u%04x", (int) c));
                    } else {
                        s.append(c);
                    }
                }
            }
        }
        return s.append('"').toString();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
