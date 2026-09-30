package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.TipoDia;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificadorDiaServiceTest {

    private static final LocalDate NOSSA_SENHORA_APARECIDA = LocalDate.of(2026, 10, 12);

    private final ClassificadorDiaService classificador =
            new ClassificadorDiaService(NOSSA_SENHORA_APARECIDA::equals);

    @Test
    void classificaDias() {
        assertThat(classificador.classificar(LocalDate.of(2026, 9, 28))).isEqualTo(TipoDia.UTIL);          // segunda
        assertThat(classificador.classificar(LocalDate.of(2026, 9, 26))).isEqualTo(TipoDia.FIM_DE_SEMANA); // sábado
        assertThat(classificador.classificar(LocalDate.of(2026, 9, 27))).isEqualTo(TipoDia.FIM_DE_SEMANA); // domingo
        assertThat(classificador.classificar(NOSSA_SENHORA_APARECIDA)).isEqualTo(TipoDia.FERIADO);
    }

    @Test
    void ausenciaSoMudaDiasUteis() {
        // férias de 05/10 a 16/10/2026: feriado e fim de semana mantêm a classificação própria
        ClassificadorDiaService comFerias = new ClassificadorDiaService(NOSSA_SENHORA_APARECIDA::equals,
                data -> !data.isBefore(LocalDate.of(2026, 10, 5)) && !data.isAfter(LocalDate.of(2026, 10, 16)));

        assertThat(comFerias.classificar(LocalDate.of(2026, 10, 5))).isEqualTo(TipoDia.AUSENCIA);
        assertThat(comFerias.classificar(LocalDate.of(2026, 10, 10))).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(comFerias.classificar(NOSSA_SENHORA_APARECIDA)).isEqualTo(TipoDia.FERIADO);
        assertThat(comFerias.classificar(LocalDate.of(2026, 10, 19))).isEqualTo(TipoDia.UTIL);
    }
}
