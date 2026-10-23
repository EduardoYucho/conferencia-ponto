package br.com.conferenciaponto.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O erro que o navegador manda não forja linhas no log que o administrador consulta. */
class ErroDeTelaNoLogTest {

    @Test
    @DisplayName("Tela e mensagem ficam numa linha só; o detalhe fica recuado, linha a linha")
    void naoForjaLinhas() {
        String forjada = "falhou\n10:00:00.000 INFO  [-] AuthController - Entrou no sistema";

        assertThat(LogController.numaLinha(forjada)).doesNotContain("\n").contains("falhou 10:00:00.000");
        assertThat(LogController.recuado("TypeError: x\r\n    at a.js:1\nlinha"))
                .isEqualTo("\n    | TypeError: x\n    |     at a.js:1\n    | linha")
                .doesNotContain("\r");
    }
}
