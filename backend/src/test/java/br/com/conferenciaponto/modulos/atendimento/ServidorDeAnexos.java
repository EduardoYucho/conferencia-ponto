package br.com.conferenciaponto.modulos.atendimento;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um armazenamento de anexos de mentira (HTTP local), no lugar do do Digisac: cada caminho tem um comportamento
 * (o arquivo inteiro, aceitar Range, cair no meio, demorar, recusar, redirecionar). Guarda os pedidos recebidos.
 */
public final class ServidorDeAnexos implements AutoCloseable {

    /** Como um caminho responde. */
    public enum Modo { NORMAL, CAI_NA_PRIMEIRA, IGNORA_RANGE, LENTO, SEM_TAMANHO }

    /** Um pedido recebido: o caminho e o cabeçalho Range (null sem Range). */
    public record Pedido(String caminho, String range) {
    }

    private record Resposta(int status, byte[] corpo, Modo modo, String destino) {
    }

    private final HttpServer servidor;
    private final Map<String, Resposta> respostas = new ConcurrentHashMap<>();
    private final Map<String, Integer> vezes = new ConcurrentHashMap<>();
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();

    public ServidorDeAnexos() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        servidor.createContext("/", this::responder);
        servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        servidor.start();
    }

    public String url(String caminho) {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + caminho;
    }

    public String base() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    public ServidorDeAnexos arquivo(String caminho, byte[] conteudo) {
        return arquivo(caminho, conteudo, Modo.NORMAL);
    }

    public ServidorDeAnexos arquivo(String caminho, byte[] conteudo, Modo modo) {
        respostas.put(caminho, new Resposta(200, conteudo, modo, null));
        return this;
    }

    public ServidorDeAnexos status(String caminho, int status) {
        respostas.put(caminho, new Resposta(status, new byte[0], Modo.NORMAL, null));
        return this;
    }

    public ServidorDeAnexos redireciona(String caminho, String destino) {
        respostas.put(caminho, new Resposta(302, new byte[0], Modo.NORMAL, destino));
        return this;
    }

    public List<Pedido> pedidos() {
        return List.copyOf(pedidos);
    }

    public long pedidosPara(String caminho) {
        return pedidos.stream().filter(p -> p.caminho().equals(caminho)).count();
    }

    private void responder(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        String range = troca.getRequestHeaders().getFirst("Range");
        pedidos.add(new Pedido(caminho, range));
        int vez = vezes.merge(caminho, 1, Integer::sum);
        Resposta r = respostas.get(caminho);
        try (troca) {
            if (r == null) {
                troca.sendResponseHeaders(404, -1);
                return;
            }
            if (r.destino() != null) {
                troca.getResponseHeaders().add("Location", r.destino());
                troca.sendResponseHeaders(r.status(), -1);
                return;
            }
            if (r.status() != 200) {
                troca.sendResponseHeaders(r.status(), -1);
                return;
            }
            byte[] corpo = r.corpo();
            long inicio = 0;
            if (range != null && r.modo() != Modo.IGNORA_RANGE && range.startsWith("bytes=")) {
                inicio = Long.parseLong(range.substring(6, range.indexOf('-')));
                troca.getResponseHeaders().add("Content-Range", "bytes " + inicio + "-" + (corpo.length - 1) + "/" + corpo.length);
                troca.sendResponseHeaders(206, corpo.length - inicio);
            } else if (r.modo() == Modo.SEM_TAMANHO) {
                troca.sendResponseHeaders(200, 0); // chunked
            } else {
                troca.sendResponseHeaders(200, corpo.length);
            }
            OutputStream saida = troca.getResponseBody();
            if (r.modo() == Modo.LENTO) {
                dormir(3000);
            }
            if (r.modo() == Modo.CAI_NA_PRIMEIRA && vez == 1) {
                saida.write(corpo, 0, corpo.length / 2);
                saida.flush();
                return; // fecha com menos bytes do que o anunciado: a conexão cai no meio
            }
            saida.write(corpo, (int) inicio, (int) (corpo.length - inicio));
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
