package br.com.conferenciaponto.modulos.atendimento.infrastructure.download;

import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.FalhaNoDownload;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * De onde o servidor aceita baixar: o link vem de um arquivo enviado pela pessoa, então só HTTPS, só os hosts da
 * lista ({@code *.dominio} vale para os subdomínios), sem usuário e senha na URL e nada da rede interna (o IP
 * resolvido é conferido: um nome que aponta para 127.0.0.1 ou 192.168.x.x é recusado).
 *
 * @param exigirHttps        false só nos testes (servidor HTTP local)
 * @param permitirRedeInterna true só nos testes
 */
public record PoliticaDeRede(List<String> hostsPermitidos, boolean exigirHttps, boolean permitirRedeInterna,
                             Resolvedor resolvedor) {

    /** Quem resolve o nome em IPs (trocável nos testes). */
    @FunctionalInterface
    public interface Resolvedor {
        InetAddress[] resolver(String host) throws UnknownHostException;
    }

    public PoliticaDeRede {
        hostsPermitidos = hostsPermitidos.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
        resolvedor = resolvedor == null ? InetAddress::getAllByName : resolvedor;
    }

    public static PoliticaDeRede producao(List<String> hostsPermitidos) {
        return new PoliticaDeRede(hostsPermitidos, true, false, null);
    }

    private static final String RECUSADO = "HOST_NAO_PERMITIDO";

    /** @throws FalhaNoDownload HOST_NAO_PERMITIDO (definitiva) ou DOWNLOAD_FALHOU (o nome não resolveu) */
    public void conferir(URI uri) {
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!esquema.equals("https") && (exigirHttps || !esquema.equals("http"))) {
            throw FalhaNoDownload.definitiva(RECUSADO, "O link do anexo não é seguro (HTTPS): por segurança, ele não é baixado. "
                    + "Envie o arquivo à mão.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null || !permitido(host)) {
            throw FalhaNoDownload.definitiva(RECUSADO, "Não baixado: o endereço do anexo está fora da lista permitida "
                    + "(atendimento.digisac.hosts-permitidos). Envie o arquivo à mão.");
        }
        if (exigirHttps && uri.getPort() != -1 && uri.getPort() != 443) {
            throw FalhaNoDownload.definitiva(RECUSADO, "Não baixado: o link do anexo usa uma porta fora do padrão. "
                    + "Envie o arquivo à mão.");
        }
        if (permitirRedeInterna) {
            return;
        }
        InetAddress[] enderecos;
        try {
            enderecos = resolvedor.resolver(host);
        } catch (UnknownHostException e) {
            throw FalhaNoDownload.temporaria("DOWNLOAD_FALHOU", "O servidor não conseguiu encontrar o endereço do anexo "
                    + "(sem internet ou sem DNS agora). Uma nova tentativa será feita.");
        }
        for (InetAddress endereco : enderecos) {
            if (interno(endereco)) {
                throw FalhaNoDownload.definitiva(RECUSADO, "Não baixado: o endereço do anexo aponta para a rede interna. "
                        + "Envie o arquivo à mão.");
            }
        }
    }

    boolean permitido(String host) {
        for (String padrao : hostsPermitidos) {
            if (padrao.startsWith("*.") ? host.endsWith(padrao.substring(1)) && host.length() > padrao.length() - 1
                    : host.equals(padrao)) {
                return true;
            }
        }
        return false;
    }

    static boolean interno(InetAddress endereco) {
        if (endereco.isAnyLocalAddress() || endereco.isLoopbackAddress() || endereco.isLinkLocalAddress()
                || endereco.isSiteLocalAddress() || endereco.isMulticastAddress()) {
            return true;
        }
        byte[] b = endereco.getAddress();
        if (endereco instanceof Inet4Address) {
            int primeiro = b[0] & 0xFF;
            int segundo = b[1] & 0xFF;
            return primeiro == 0 || primeiro == 100 && segundo >= 64 && segundo <= 127 // CGNAT 100.64/10
                    || primeiro == 198 && (segundo == 18 || segundo == 19) || primeiro >= 240;
        }
        if (endereco instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 (endereços locais únicos)
        }
        return false;
    }
}
