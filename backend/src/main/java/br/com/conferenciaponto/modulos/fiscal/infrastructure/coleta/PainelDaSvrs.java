package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um painel de disponibilidade da SVRS (uma página por documento). Baixa a página uma vez por rodada (o painel se
 * atualiza a cada 5 minutos), com tempo limite e identificação do sistema, e entrega a leitura ao
 * {@link LeitorDoPainelSvrs}. Erro de rede, resposta diferente de 200 ou página sem nenhum serviço viram "fonte com
 * problema", nunca "tudo normal".
 */
public class PainelDaSvrs implements FonteDeLeituras {

    /** O painel mostra a hora de Brasília. */
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    static final String IDENTIFICACAO = "ConferenciaPonto-MonitorFiscal/1.0 (uma consulta a cada 5 minutos)";
    private static final int TAMANHO_MAXIMO = 5 * 1024 * 1024;
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([\\w.:-]+)");

    private final Documento documento;
    private final URI endereco;
    private final HttpClient http;
    private final Duration tempoLimite;
    private final Duration verificacaoAntiga;
    private final LeitorDoPainelSvrs leitor = new LeitorDoPainelSvrs();

    public PainelDaSvrs(Documento documento, String endereco, HttpClient http, Duration tempoLimite,
                        Duration verificacaoAntiga) {
        this.documento = documento;
        this.endereco = URI.create(endereco.strip());
        this.http = http;
        this.tempoLimite = tempoLimite;
        this.verificacaoAntiga = verificacaoAntiga;
    }

    @Override
    public String nome() {
        return "painel-svrs-" + documento.name().toLowerCase(Locale.ROOT);
    }

    public Documento documento() {
        return documento;
    }

    @Override
    public Resultado ler(Instant agora) {
        HttpRequest pedido = HttpRequest.newBuilder(endereco)
                .timeout(tempoLimite)
                .header("User-Agent", IDENTIFICACAO)
                .header("Accept", "text/html")
                .GET()
                .build();
        HttpResponse<byte[]> resposta;
        try {
            resposta = http.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            return Resultado.falhou(nome(), "O portal não aceitou a conexão em %d s.".formatted(tempoLimite.toSeconds()));
        } catch (HttpTimeoutException e) {
            return Resultado.falhou(nome(), "O portal não respondeu em %d s.".formatted(tempoLimite.toSeconds()));
        } catch (SSLException e) {
            // houve resposta do outro lado: o problema é a conexão segura (ex.: certificado em que este Java não confia)
            return new Resultado(nome(), true, "Falha na conexão segura com o portal: " + mensagem(e), List.of(),
                    null, null);
        } catch (ConnectException e) {
            return Resultado.falhou(nome(), causa(e, UnknownHostException.class).isPresent()
                    ? "Endereço do portal não encontrado (DNS)." : "Não conectou ao portal: " + mensagem(e));
        } catch (IOException e) {
            return Resultado.falhou(nome(), "Erro de rede ao ler o portal: " + mensagem(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resultado.falhou(nome(), "Leitura interrompida.");
        }
        if (resposta.statusCode() != 200) {
            return new Resultado(nome(), true, "O portal respondeu HTTP %d.".formatted(resposta.statusCode()),
                    List.of(), null, null);
        }
        byte[] corpo = resposta.body();
        if (corpo.length > TAMANHO_MAXIMO) {
            return new Resultado(nome(), true, "A página do portal veio grande demais.", List.of(), null, null);
        }
        String charset = resposta.headers().firstValue("Content-Type").map(CHARSET::matcher)
                .filter(Matcher::find).map(m -> m.group(1)).orElse(null);
        LeitorDoPainelSvrs.Lido lido = leitor.ler(corpo, charset, documento, agora, FUSO, verificacaoAntiga);
        return new Resultado(nome(), true, lido.erro(), lido.leituras(), documento, lido.aviso());
    }

    private static <T extends Throwable> Optional<T> causa(Throwable e, Class<T> tipo) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (tipo.isInstance(t)) {
                return Optional.of(tipo.cast(t));
            }
        }
        return Optional.empty();
    }

    private static String mensagem(Throwable e) {
        String m = e.getMessage();
        if ((m == null || m.isBlank()) && e.getCause() != null) {
            m = e.getCause().getMessage();
        }
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.strip();
    }
}
