package br.com.expedio.modulos.backup.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FiltroDaPortaPublicaTest {

    private final Bloqueios bloqueios = new Bloqueios();
    private final FiltroDaPortaPublica filtro = new FiltroDaPortaPublica(8086, bloqueios);

    private MockHttpServletResponse pedir(int porta, String metodo, String caminho, String ipDoCloudflare) throws Exception {
        MockHttpServletRequest requisicao = new MockHttpServletRequest(metodo, caminho);
        requisicao.setLocalPort(porta);
        if (ipDoCloudflare != null) {
            requisicao.addHeader("CF-Connecting-IP", ipDoCloudflare);
        }
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();
        filtro.doFilter(requisicao, resposta, cadeia);
        if (cadeia.getRequest() != null) {
            resposta.setStatus(299); // passou adiante
        }
        return resposta;
    }

    private MockHttpServletResponse pedir(int porta, String metodo, String caminho) throws Exception {
        return pedir(porta, metodo, caminho, null);
    }

    @Test
    void portaPublicaSoAtendeAPaginaDeColeta() throws Exception {
        assertThat(pedir(8086, "GET", "/c/" + "A".repeat(43)).getStatus()).isEqualTo(299);
        assertThat(pedir(8086, "PUT", "/c/x/api/partes/0").getStatus()).isEqualTo(299);
        assertThat(pedir(8086, "GET", "/c/_/coleta.js").getStatus()).isEqualTo(299);
        assertThat(pedir(8086, "GET", "/").getStatus()).isEqualTo(404);
        assertThat(pedir(8086, "GET", "/index.html").getStatus()).isEqualTo(404);
        assertThat(pedir(8086, "POST", "/api/v1/auth/login").getStatus()).isEqualTo(404);
        assertThat(pedir(8086, "GET", "/api/v1/backup/coletas").getStatus()).isEqualTo(404);
        assertThat(pedir(8086, "GET", "/usuarios").getStatus()).isEqualTo(404);
    }

    @Test
    void portaDeSempreNaoMuda() throws Exception {
        assertThat(pedir(8080, "GET", "/").getStatus()).isEqualTo(299);
        assertThat(pedir(8080, "POST", "/api/v1/auth/login").getStatus()).isEqualTo(299);
        assertThat(pedir(8080, "GET", "/").getHeader("Content-Security-Policy")).as("o resto do sistema não muda").isNull();
    }

    @Test
    void paginaDeColetaComCabecalhosDeProtecao() throws Exception {
        MockHttpServletResponse r = pedir(8086, "GET", "/c/" + "A".repeat(43));
        assertThat(r.getHeader("Content-Security-Policy"))
                .contains("default-src 'none'", "script-src 'self' 'wasm-unsafe-eval'", "connect-src 'self'", "frame-ancestors 'none'");
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void quemTestaLinksEhBarrado() throws Exception {
        for (int i = 0; i < Bloqueios.TENTATIVAS; i++) {
            bloqueios.registrarErro("203.0.113.9");
        }
        assertThat(pedir(8086, "GET", "/c/" + "A".repeat(43), "203.0.113.9").getStatus()).isEqualTo(429);
        assertThat(pedir(8086, "GET", "/c/" + "A".repeat(43), "198.51.100.7").getStatus()).as("os outros seguem").isEqualTo(299);
        // o cabeçalho do Cloudflare só vale pela porta do túnel (pela rede, qualquer um poderia inventá-lo)
        MockHttpServletRequest pelaRede = new MockHttpServletRequest("GET", "/c/x");
        pelaRede.setLocalPort(8080);
        pelaRede.setRemoteAddr("10.0.0.5");
        pelaRede.addHeader("CF-Connecting-IP", "203.0.113.9");
        assertThat(Bloqueios.enderecoDe(pelaRede, 8086)).isEqualTo("10.0.0.5");
    }
}
