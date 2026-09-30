package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia.Achado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ComparadorConciliacaoServiceTest {

    private static final LocalDate DIA = LocalDate.of(2026, 6, 24);
    private final ComparadorConciliacaoService comparador = new ComparadorConciliacaoService();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();

    private static List<LocalTime> h(String... horarios) {
        return Arrays.stream(horarios).map(LocalTime::parse).toList();
    }

    private static DiaRelatorioRh rh(String ocorrencia, int prevista, int saldo, String... horarios) {
        return new DiaRelatorioRh(DIA, h(horarios), ocorrencia, prevista, 0, saldo);
    }

    private Optional<RegistroJornada> local(String... horarios) {
        RegistroJornada r = RegistroJornada.novo(DIA, TipoDia.UTIL);
        for (LocalTime t : h(horarios)) {
            r.incluirBatida(t, motor);
        }
        return Optional.of(r);
    }

    private Optional<Achado> comparar(DiaRelatorioRh rh, Optional<RegistroJornada> local) {
        return comparador.comparar(rh, local, TipoDia.UTIL);
    }

    @Test
    @DisplayName("Mesmas batidas e mesmo saldo: nada a conciliar")
    void iguais() {
        assertThat(comparar(rh(null, 31_680, 569, "08:05:52", "12:00:25", "12:58:32", "18:03:21"),
                local("08:05:52", "12:00:25", "12:58:32", "18:03:21"))).isEmpty();
    }

    @Test
    @DisplayName("1 s de diferença que não muda o saldo é ignorado; que muda o saldo vira DIFERENCA_SEGUNDOS")
    void segundos() {
        // 12:00:26 x 12:00:25: dentro da tolerância dos dois lados, saldo igual
        assertThat(comparar(rh(null, 31_680, 569, "08:05:52", "12:00:25", "12:58:32", "18:03:21"),
                local("08:05:52", "12:00:26", "12:58:32", "18:03:21"))).isEmpty();

        Optional<Achado> a = comparar(rh(null, 31_680, 569, "08:05:52", "12:00:25", "12:58:32", "18:03:21"),
                local("08:05:53", "12:00:25", "12:58:32", "18:03:21")); // entrada fora da tolerância, 1 s depois
        assertThat(a).hasValueSatisfying(x -> {
            assertThat(x.tipo()).isEqualTo(TipoDivergencia.DIFERENCA_SEGUNDOS);
            assertThat(x.aceitavel()).isTrue();
            assertThat(x.descricao()).contains("+00:09:29").contains("+00:09:28");
        });
    }

    @Test
    @DisplayName("Batida que o RH corrigiu e falta aqui; batida a mais; horário diferente")
    void batidas() {
        DiaRelatorioRh corrigido = rh(null, 31_680, 569, "08:05:52", "12:00:25", "12:58:32", "18:03:21");
        assertThat(comparar(corrigido, local("08:05:53", "12:58:33", "18:03:22"))).hasValueSatisfying(x -> {
            assertThat(x.tipo()).isEqualTo(TipoDivergencia.BATIDA_FALTANDO);
            assertThat(x.descricao()).contains("Falta na conferência: 12:00:25");
        });
        assertThat(comparar(rh(null, 31_680, 0, "08:00:00", "17:48:00"), local("08:00:00", "12:00:00", "13:00:00", "17:48:00")))
                .hasValueSatisfying(x -> assertThat(x.tipo()).isEqualTo(TipoDivergencia.BATIDA_SOBRANDO));
        assertThat(comparar(corrigido, local("08:05:52", "12:10:00", "12:58:32", "18:03:21")))
                .hasValueSatisfying(x -> {
                    assertThat(x.tipo()).isEqualTo(TipoDivergencia.HORARIO_DIFERENTE);
                    assertThat(x.descricao()).contains("RH 12:00:25 × conferência 12:10:00");
                });
    }

    @Test
    @DisplayName("Dia só no RH (histórico); com número ímpar de batidas não dá para copiar")
    void somenteRh() {
        assertThat(comparar(rh(null, 31_680, 183, "07:56:59", "12:00:33", "12:54:38", "13:26:26", "13:44:55", "18:04:10"),
                Optional.empty())).hasValueSatisfying(x -> {
            assertThat(x.tipo()).isEqualTo(TipoDivergencia.SOMENTE_RH);
            assertThat(x.aceitavel()).isTrue();
        });
        assertThat(comparar(rh(null, 31_680, 61, "08:04:59", "12:00:20", "13:02:49", "17:49:01", "17:49:46"),
                Optional.empty())).hasValueSatisfying(x -> {
            assertThat(x.aceitavel()).isFalse();
            assertThat(x.motivoNaoAceitavel()).contains("ímpar");
        });
        // fim de semana sem batidas dos dois lados
        assertThat(comparador.comparar(rh(null, 0, 0), Optional.empty(), TipoDia.FIM_DE_SEMANA)).isEmpty();
    }

    @Test
    @DisplayName("Batida repetida (clique duplo) só no RH: vira diferença de saldo, não de batidas")
    void cliqueDuplo() {
        assertThat(comparar(rh(null, 31_680, 61, "08:04:59", "12:00:20", "13:02:49", "17:49:01", "17:49:46"),
                local("08:04:59", "12:00:20", "13:02:49", "17:49:01"))).hasValueSatisfying(x -> {
            assertThat(x.tipo()).isEqualTo(TipoDivergencia.SALDO);
            assertThat(x.aceitavel()).isFalse();
        });
    }

    @Test
    @DisplayName("Tipo do dia: feriado/férias/folga do RH num dia útil daqui, e o contrário")
    void tipoDoDia() {
        assertThat(comparar(rh("Feriado", 0, 0), Optional.empty()))
                .hasValueSatisfying(x -> assertThat(x.tipo()).isEqualTo(TipoDivergencia.TIPO_DIA));
        assertThat(comparar(rh("folga aniversár", 0, 0), Optional.empty()))
                .hasValueSatisfying(x -> assertThat(x.aceitavel()).isTrue());
        assertThat(comparador.comparar(rh("Férias", 0, 0), Optional.empty(), TipoDia.AUSENCIA)).isEmpty();
        assertThat(comparador.comparar(rh("Férias", 0, 0), Optional.empty(), TipoDia.FIM_DE_SEMANA)).isEmpty();
        assertThat(comparador.comparar(rh(null, 31_680, -31_680), Optional.empty(), TipoDia.FERIADO))
                .hasValueSatisfying(x -> {
                    assertThat(x.tipo()).isEqualTo(TipoDivergencia.TIPO_DIA);
                    assertThat(x.aceitavel()).isFalse();
                });
    }

    @Test
    @DisplayName("Batidas só na conferência (ex.: sábado que o RH não tem)")
    void somenteLocal() {
        assertThat(comparar(rh(null, 0, 0), local("09:00:05", "10:00:07")))
                .hasValueSatisfying(x -> {
                    assertThat(x.tipo()).isEqualTo(TipoDivergencia.SOMENTE_LOCAL);
                    assertThat(x.aceitavel()).isFalse();
                });
    }
}
