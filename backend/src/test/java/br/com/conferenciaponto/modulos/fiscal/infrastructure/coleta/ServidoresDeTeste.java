package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Servidores locais para os testes de conexão: um que responde lixo (não fala TLS, mas responde), um que aceita e
 * fica calado, um que demora para responder. Cada um numa porta livre de 127.0.0.1.
 */
final class ServidoresDeTeste implements AutoCloseable {

    private final List<ServerSocket> servidores = new ArrayList<>();
    private final List<Socket> conexoes = new ArrayList<>();

    /** Responde "isto não é TLS" a quem conectar, depois de {@code demoraMs}. */
    int queResponde(long demoraMs) throws IOException {
        return iniciar(cliente -> {
            Thread.sleep(demoraMs);
            OutputStream out = cliente.getOutputStream();
            out.write("HTTP/1.1 400 isto nao e TLS\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(200);
            cliente.close();
        });
    }

    /** Aceita a conexão e não manda nada. */
    int calado() throws IOException {
        return iniciar(cliente -> { /* segura a conexão aberta até o fim do teste */ });
    }

    /** Uma porta em que ninguém escuta. */
    static int fechada() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    interface Atendimento {
        void atender(Socket cliente) throws Exception;
    }

    private int iniciar(Atendimento atendimento) throws IOException {
        ServerSocket servidor = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        servidores.add(servidor);
        Thread t = new Thread(() -> {
            while (!servidor.isClosed()) {
                try {
                    Socket cliente = servidor.accept();
                    synchronized (conexoes) {
                        conexoes.add(cliente);
                    }
                    Thread atende = new Thread(() -> {
                        try {
                            atendimento.atender(cliente);
                        } catch (Exception ignorado) {
                            // o teste terminou ou o cliente desistiu
                        }
                    });
                    atende.setDaemon(true);
                    atende.start();
                } catch (IOException fechado) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return servidor.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        for (ServerSocket s : servidores) {
            s.close();
        }
        synchronized (conexoes) {
            for (Socket c : conexoes) {
                c.close();
            }
        }
    }
}
