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
import java.util.ArrayList;
import java.util.List;

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

    /** Acha os endereços IP de um nome (o DNS; nos testes, um falso). */
    interface Resolvedor {
        InetAddress[] resolver(String host) throws UnknownHostException;
    }

    private static final int PORTA_PADRAO = 443;
    /** Um nome com vários servidores (DNS round robin): testa até este tanto, um depois do outro. */
    private static final int SERVIDORES_TESTADOS = 3;

    private final Duration tempoLimite;
    private final Duration lento;
    private final SSLSocketFactory tls;
    private final Resolvedor resolvedor;

    public SondaDeConexao(Duration tempoLimite, Duration lento) {
        this(tempoLimite, lento, (SSLSocketFactory) SSLSocketFactory.getDefault(), InetAddress::getAllByName);
    }

    SondaDeConexao(Duration tempoLimite, Duration lento, SSLSocketFactory tls, Resolvedor resolvedor) {
        this.tempoLimite = tempoLimite;
        this.lento = lento;
        this.tls = tls;
        this.resolvedor = resolvedor;
    }

    /**
     * @param endereco "host" ou "host:porta" (443 se não tiver porta). Um nome com mais de um servidor: se um não
     *                 responde e outro sim, o endereço está instável (parte dos pedidos dos clientes cai no que falha)
     */
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
        InetAddress[] ips;
        try {
            ips = resolvedor.resolver(host);
        } catch (UnknownHostException e) {
            return new Resposta(Situacao.FORA, "Endereço não encontrado (DNS).", null, false);
        }
        if (ips == null || ips.length == 0) {
            return new Resposta(Situacao.FORA, "Endereço não encontrado (DNS).", null, false);
        }
        int dnsMs = decorrido(inicio);
        int total = Math.min(ips.length, SERVIDORES_TESTADOS);
        List<Resposta> falhas = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            long resta = tempoLimite.toMillis() - decorrido(inicio);
            if (resta <= 0) {
                break;
            }
            Resposta r = tentar(ips[i], host, porta, resta, dnsMs);
            if (r.situacao() != Situacao.FORA) {
                if (falhas.isEmpty()) {
                    return r;
                }
                return new Resposta(Situacao.INSTAVEL, "%d de %d servidores deste endereço não responderam (%s); outro respondeu em %d ms."
                        .formatted(falhas.size(), ips.length, minusculaNoInicio(falhas.get(0).detalhe()), r.tempoMs()),
                        r.tempoMs(), true);
            }
            falhas.add(r);
        }
        Resposta primeira = falhas.isEmpty()
                ? new Resposta(Situacao.FORA, "Sem resposta em %d s.".formatted(tempoLimite.toSeconds()), null, false)
                : falhas.get(0);
        if (ips.length > 1 && falhas.size() > 1) {
            return new Resposta(Situacao.FORA, "%s (%d de %d servidores testados)".formatted(
                    primeira.detalhe().replaceAll("\\.$", ""), falhas.size(), ips.length) + ".", null,
                    falhas.stream().anyMatch(Resposta::respondeu));
        }
        return primeira;
    }

    /** Um servidor: conexão e começo da conexão segura, com o tempo medido até o fim do handshake. */
    private Resposta tentar(InetAddress ip, String host, int porta, long limiteMs, int dnsMs) {
        long inicio = System.nanoTime();
        String segundos = "%d s".formatted(tempoLimite.toSeconds());
        // o fechamento é da conexão de baixo: fechar a conexão segura espera o "close_notify" do servidor, e há
        // servidor que não manda (o teste ficava 15 s esperando à toa)
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(ip, porta), (int) Math.max(1, limiteMs));
            } catch (SocketTimeoutException e) {
                return new Resposta(Situacao.FORA, "Sem resposta em %s (conexão).".formatted(segundos), null, false);
            } catch (ConnectException e) {
                return new Resposta(Situacao.FORA, "Conexão recusada pelo servidor.", null, true);
            } catch (NoRouteToHostException e) {
                return new Resposta(Situacao.FORA, "Sem rota até o servidor.", null, false);
            }
            int resta = (int) Math.max(1, limiteMs - decorrido(inicio));
            socket.setSoTimeout(resta);
            try {
                SSLSocket seguro = (SSLSocket) tls.createSocket(socket, host, porta, false);
                seguro.setSoTimeout(resta);
                seguro.startHandshake();
            } catch (SocketTimeoutException e) {
                return new Resposta(Situacao.FORA, "Conectou, mas não respondeu à conexão segura em %s."
                        .formatted(segundos), null, true);
            } catch (SSLException e) {
                if (causa(e, SocketTimeoutException.class)) {
                    return new Resposta(Situacao.FORA, "Conectou, mas não respondeu à conexão segura em %s."
                            .formatted(segundos), null, true);
                }
                if (causa(e, SocketException.class)) {
                    return new Resposta(Situacao.INSTAVEL, "A conexão caiu durante a conexão segura.",
                            dnsMs + decorrido(inicio), true);
                }
                // o servidor respondeu ao TLS (pediu o certificado, recusou o cliente, mandou um alerta): está lá
            } catch (SocketException e) {
                return new Resposta(Situacao.INSTAVEL, "A conexão caiu durante a conexão segura.",
                        dnsMs + decorrido(inicio), true);
            }
            int ms = dnsMs + decorrido(inicio);
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

    private static String minusculaNoInicio(String texto) {
        String t = texto.replaceAll("\\.$", "");
        return t.isEmpty() ? t : Character.toLowerCase(t.charAt(0)) + t.substring(1);
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
