package br.com.conferenciaponto.modulos.backup.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FiltroDaPortaPublicaTest {

    private final FiltroDaPortaPublica filtro = new FiltroDaPortaPublica(8086);

    private MockHttpServletResponse pedir(int porta, String metodo, String caminho) throws Exception {
        MockHttpServletRequest requisicao = new MockHttpServletRequest(metodo, caminho);
        requisicao.setLocalPort(porta);
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();
        filtro.doFilter(requisicao, resposta, cadeia);
        if (cadeia.getRequest() != null) {
            resposta.setStatus(299); // passou adiante
        }
        return resposta;
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
        assertThat(pedir(8080, "GET", "/c/" + "A".repeat(43)).getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
