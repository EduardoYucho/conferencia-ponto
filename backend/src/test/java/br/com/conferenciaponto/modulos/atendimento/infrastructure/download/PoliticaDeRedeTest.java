package br.com.conferenciaponto.modulos.atendimento.infrastructure.download;

import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.FalhaNoDownload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** De onde o servidor aceita baixar: só HTTPS, só os hosts da lista e nada da rede interna. */
class PoliticaDeRedeTest {

    private static final String DIGISAC = "*.compat.objectstorage.sa-vinhedo-1.oraclecloud.com";
    private static final URI LINK = URI.create("https://bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com/x/a.jpeg?X-Amz-Signature=1");

    private static PoliticaDeRede resolvendoPara(String ip) {
        return new PoliticaDeRede(List.of(DIGISAC), true, false, host -> new InetAddress[] {InetAddress.getByName(ip)});
    }

    private static void recusado(PoliticaDeRede politica, URI uri, String codigo) {
        assertThatThrownBy(() -> politica.conferir(uri)).isInstanceOf(FalhaNoDownload.class)
                .hasFieldOrPropertyWithValue("codigo", codigo).hasFieldOrPropertyWithValue("temporaria", false);
    }

    @Test
    void aceitaOArmazenamentoDoDigisacComIpPublico() {
        assertThatCode(() -> resolvendoPara("152.70.48.10").conferir(LINK)).doesNotThrowAnyException();
    }

    @Test
    void soHttps() {
        recusado(resolvendoPara("152.70.48.10"), URI.create("http://bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com/a"),
                "HOST_NAO_PERMITIDO");
        recusado(resolvendoPara("152.70.48.10"), URI.create("ftp://bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com/a"),
                "HOST_NAO_PERMITIDO");
    }

    @Test
    void soOsHostsDaLista() {
        PoliticaDeRede politica = resolvendoPara("152.70.48.10");
        recusado(politica, URI.create("https://evil.example.com/a"), "HOST_NAO_PERMITIDO");
        recusado(politica, URI.create("https://compat.objectstorage.sa-vinhedo-1.oraclecloud.com.evil.com/a"), "HOST_NAO_PERMITIDO");
        recusado(politica, URI.create("https://bucketcompat.objectstorage.sa-vinhedo-1.oraclecloud.com/a"), "HOST_NAO_PERMITIDO");
        recusado(politica, URI.create("https://compat.objectstorage.sa-vinhedo-1.oraclecloud.com/a"), "HOST_NAO_PERMITIDO");
    }

    @Test
    void semUsuarioNaUrlESemPortaForaDoPadrao() {
        PoliticaDeRede politica = resolvendoPara("152.70.48.10");
        recusado(politica, URI.create("https://user:senha@bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com/a"),
                "HOST_NAO_PERMITIDO");
        recusado(politica, URI.create("https://bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com:8443/a"),
                "HOST_NAO_PERMITIDO");
        assertThatCode(() -> politica.conferir(URI.create("https://bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com:443/a")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "192.168.1.20", "172.16.3.4", "169.254.169.254", "100.64.1.1",
            "0.0.0.0", "::1", "fc00::1", "fe80::1", "224.0.0.1"})
    void recusaONomeQueApontaParaARedeInterna(String ip) {
        recusado(resolvendoPara(ip), LINK, "HOST_NAO_PERMITIDO");
    }

    @Test
    void nomeQueNaoResolveETemporario() {
        PoliticaDeRede semDns = new PoliticaDeRede(List.of(DIGISAC), true, false, host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> semDns.conferir(LINK)).hasFieldOrPropertyWithValue("codigo", "DOWNLOAD_FALHOU")
                .hasFieldOrPropertyWithValue("temporaria", true);
    }
}
