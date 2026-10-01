package br.com.conferenciaponto.infrastructure.web.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LancamentoBancoRequestTest {

    @ParameterizedTest(name = "{0} {1} → {2} s")
    @DisplayName("Duração em horas:minutos[:segundos] vira segundos; DEBITO é negativo")
    @CsvSource({
            "04:00,    DEBITO,  -14400",
            "8:48,     DEBITO,  -31680",
            "40:00,    DEBITO,  -144000",
            "01:30:15, CREDITO, 5415",
            "0:05,     CREDITO, 300",
    })
    void segundos(String duracao, LancamentoBancoRequest.Sentido sentido, int esperado) {
        assertThat(new LancamentoBancoRequest(LocalDate.of(2026, 9, 26), duracao, sentido, "x").segundos())
                .isEqualTo(esperado);
    }
}
