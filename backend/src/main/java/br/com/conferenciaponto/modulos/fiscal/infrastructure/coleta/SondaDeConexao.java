package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Teste de conexão com um servidor de autorização, sem certificado: acha o endereço (DNS), abre a conexão (TCP) e
 * começa a conexão segura (TLS). Nada é enviado além do começo do TLS. Os servidores das SEFAZ pedem o certificado
 * do cliente e encerram o TLS sem ele; isso conta como "respondeu": o que se mede é se o servidor está lá e em
 * quanto tempo responde. Uma falha depois da autenticação não aparece aqui (só com certificado, na etapa M6).
 */
public class SondaDeConexao {

    /** O que o teste viu; {@code respondeu} = algum servidor lá fora falou com este computador. */
    public record Resposta(Situacao situacao, String detalhe, Integer tempoMs, boolean respondeu) {
    }

    private static final int PORTA_PADRAO = 443;

    private final Duration tempoLimite;
    private final Duration lento;
    private final SSLSocketFactory tls;

    public SondaDeConexao(Duration tempoLimite, Duration lento) {
        this(tempoLimite, lento, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    SondaDeConexao(Duration tempoLimite, Duration lento, SSLSocketFactory tls) {
        this.tempoLimite = tempoLimite;
        this.lento = lento;
        this.tls = tls;
    }

    /** @param endereco "host" ou "host:porta" (443 se não tiver porta) */
    public Resposta testar(String endereco) {
        String host = endereco.strip();
        int porta = PORTA_PADRAO;
        int doisPontos = host.lastIndexOf(':');
        if (doisPontos > 0 && host.indexOf(':') == doisPontos) {
            try {
                porta = Integer.parseInt(host.substring(doisPontos + 1));
                host = host.substring(0, doisPontos);
            } catch (NumberFormatException e) {
                return new Resposta(Situacao.SEM_INFORMACAO, "Endereço inválido na configuração: " + endereco, null, false);
            }
        }
        long inicio = System.nanoTime();
        long limite = tempoLimite.toMillis();
        InetAddress ip;
        try {
            ip = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return new Resposta(Situacao.FORA, "Endereço não encontrado (DNS).", null, false);
        }
        try (Socket socket = new Socket()) {
            int resta = (int) Math.max(1, limite - decorrido(inicio));
            try {
                socket.connect(new InetSocketAddress(ip, porta), resta);
            } catch (SocketTimeoutException e) {
                return new Resposta(Situacao.FORA, "Sem resposta em %d s (conexão).".formatted(tempoLimite.toSeconds()),
                        null, false);
            } catch (ConnectException e) {
                return new Resposta(Situacao.FORA, "Conexão recusada pelo servidor.", null, true);
            } catch (NoRouteToHostException e) {
                return new Resposta(Situacao.FORA, "Sem rota até o servidor.", null, false);
            }
            socket.setSoTimeout((int) Math.max(1, limite - decorrido(inicio)));
            try (SSLSocket seguro = (SSLSocket) tls.createSocket(socket, host, porta, false)) {
                seguro.setSoTimeout((int) Math.max(1, limite - decorrido(inicio)));
                seguro.startHandshake();
            } catch (SocketTimeoutException e) {
                return new Resposta(Situacao.FORA, "Conectou, mas não respondeu à conexão segura em %d s."
                        .formatted(tempoLimite.toSeconds()), null, true);
            } catch (SSLException e) {
                if (causa(e, SocketTimeoutException.class)) {
                    return new Resposta(Situacao.FORA, "Conectou, mas não respondeu à conexão segura em %d s."
                            .formatted(tempoLimite.toSeconds()), null, true);
                }
                if (causa(e, SocketException.class)) {
                    return comTempo(inicio, Situacao.INSTAVEL, "A conexão caiu durante a conexão segura.");
                }
                // o servidor respondeu ao TLS (pediu o certificado, recusou o cliente, mandou um alerta): está lá
            } catch (SocketException e) {
                return comTempo(inicio, Situacao.INSTAVEL, "A conexão caiu durante a conexão segura.");
            }
            int ms = decorrido(inicio);
            if (ms > lento.toMillis()) {
                return new Resposta(Situacao.INSTAVEL, "Respondeu, mas devagar: %d ms (o normal é menos de %d ms)."
                        .formatted(ms, lento.toMillis()), ms, true);
            }
            return new Resposta(Situacao.NORMAL, "Respondeu em %d ms (teste de conexão, sem certificado).".formatted(ms),
                    ms, true);
        } catch (IOException e) {
            return new Resposta(Situacao.FORA, "Erro de rede: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()), null, false);
        }
    }

    private static Resposta comTempo(long inicio, Situacao situacao, String detalhe) {
        return new Resposta(situacao, detalhe, decorrido(inicio), true);
    }

    private static int decorrido(long inicio) {
        return (int) Duration.ofNanos(System.nanoTime() - inicio).toMillis();
    }

    private static boolean causa(Throwable e, Class<? extends Throwable> tipo) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
