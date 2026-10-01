package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.TipoDia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificadorDiaServiceTest {

    private static final LocalDate NOSSA_SENHORA_APARECIDA = LocalDate.of(2026, 10, 12);
    private static final UUID USUARIO = UUID.randomUUID();
    private static final HorarioTrabalho SEG_A_SEX = HorarioTrabalho.semanal(USUARIO, HorarioTrabalho.DESDE_SEMPRE,
            GradeHoraria.PADRAO, 5, "teste", Instant.EPOCH);

    private final ClassificadorDiaService classificador =
            new ClassificadorDiaService(NOSSA_SENHORA_APARECIDA::equals);

    @Test
    void classificaDias() {
        assertThat(classificador.classificar(USUARIO, LocalDate.of(2026, 9, 28), SEG_A_SEX)).isEqualTo(TipoDia.UTIL);
        assertThat(classificador.classificar(USUARIO, LocalDate.of(2026, 9, 26), SEG_A_SEX)).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(classificador.classificar(USUARIO, LocalDate.of(2026, 9, 27), SEG_A_SEX)).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(classificador.classificar(USUARIO, NOSSA_SENHORA_APARECIDA, SEG_A_SEX)).isEqualTo(TipoDia.FERIADO);
    }

    @Test
    @DisplayName("Quem trabalha de terça a sábado: sábado é dia útil e segunda é sem expediente")
    void horarioDeCadaUsuario() {
        EnumMap<DayOfWeek, GradeHoraria> dias = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : List.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY)) {
            dias.put(d, GradeHoraria.ler("07:00-11:00 12:00-16:00"));
        }
        HorarioTrabalho terASab = new HorarioTrabalho(UUID.randomUUID(), USUARIO, HorarioTrabalho.DESDE_SEMPRE, 10,
                dias, Instant.EPOCH, "teste");

        assertThat(classificador.classificar(USUARIO, LocalDate.of(2026, 9, 26), terASab)).isEqualTo(TipoDia.UTIL);
        assertThat(classificador.classificar(USUARIO, LocalDate.of(2026, 9, 28), terASab)).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(terASab.previstoSegundos(LocalDate.of(2026, 9, 26))).isEqualTo(8 * 3600);
        assertThat(terASab.motor(LocalDate.of(2026, 9, 26)).toleranciaMinutos()).isEqualTo(10);
    }

    @Test
    void ausenciaSoMudaDiasUteis() {
        // férias de 05/10 a 16/10/2026: feriado e fim de semana mantêm a classificação própria
        ClassificadorDiaService comFerias = new ClassificadorDiaService(NOSSA_SENHORA_APARECIDA::equals,
                (usuario, data) -> usuario.equals(USUARIO)
                        && !data.isBefore(LocalDate.of(2026, 10, 5)) && !data.isAfter(LocalDate.of(2026, 10, 16)));

        assertThat(comFerias.classificar(USUARIO, LocalDate.of(2026, 10, 5), SEG_A_SEX)).isEqualTo(TipoDia.AUSENCIA);
        assertThat(comFerias.classificar(USUARIO, LocalDate.of(2026, 10, 10), SEG_A_SEX)).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(comFerias.classificar(USUARIO, NOSSA_SENHORA_APARECIDA, SEG_A_SEX)).isEqualTo(TipoDia.FERIADO);
        assertThat(comFerias.classificar(USUARIO, LocalDate.of(2026, 10, 19), SEG_A_SEX)).isEqualTo(TipoDia.UTIL);
        // as férias são só de quem as cadastrou
        assertThat(comFerias.classificar(UUID.randomUUID(), LocalDate.of(2026, 10, 5), SEG_A_SEX)).isEqualTo(TipoDia.UTIL);
    }
}
