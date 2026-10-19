package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um Ollama de mentira (HTTP local): as respostas de {@code /api/chat} e {@code /api/embed} saem da fila (ou da
 * padrão); {@code /api/ps} diz quanto da placa o modelo ocupa. Guarda os pedidos recebidos (caminho e corpo).
 */
final class OllamaDeMentira implements AutoCloseable {

    record Pedido(String caminho, String corpo) {
    }

    private record Resposta(int status, String corpo) {
    }

    private final HttpServer servidor;
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    private final Deque<Resposta> respostas = new ArrayDeque<>();
    private volatile String padrao = conversa("{\"descricao\":\"Tela de pedidos\"}", "stop");
    private volatile long memoriaDoModelo;

    OllamaDeMentira() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        servidor.createContext("/", this::responder);
        servidor.start();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    }

    static String conversa(String conteudo, String fim) {
        return "{\"model\":\"gemma4:12b\",\"message\":{\"role\":\"assistant\",\"content\":" + texto(conteudo) + "},"
                + "\"done\":true,\"done_reason\":\"" + fim + "\",\"prompt_eval_count\":1200,\"eval_count\":80}";
    }

    static String texto(String valor) {
        return "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    synchronized OllamaDeMentira responder(int status, String corpo) {
        respostas.addLast(new Resposta(status, corpo));
        return this;
    }

    OllamaDeMentira memoriaDoModelo(long bytes) {
        this.memoriaDoModelo = bytes;
        return this;
    }

    List<Pedido> pedidos() {
        return List.copyOf(pedidos);
    }

    List<Pedido> pedidos(String caminho) {
        return pedidos.stream().filter(p -> p.caminho().equals(caminho)).toList();
    }

    private void responder(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        pedidos.add(new Pedido(caminho, corpo));
        Resposta resposta;
        if (caminho.equals("/api/ps")) {
            resposta = new Resposta(200, memoriaDoModelo > 0
                    ? "{\"models\":[{\"name\":\"gemma4:12b\",\"size_vram\":" + memoriaDoModelo + "}]}" : "{\"models\":[]}");
        } else if (caminho.equals("/api/generate")) {
            resposta = new Resposta(200, "{\"done\":true}");
        } else {
            synchronized (this) {
                resposta = respostas.isEmpty() ? new Resposta(200, padrao) : respostas.removeFirst();
            }
        }
        byte[] bytes = resposta.corpo().getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(resposta.status(), bytes.length);
        troca.getResponseBody().write(bytes);
        troca.close();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
