package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.AvisoLegal.Nivel;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeiDaJornadaTest {

    private static final UUID PESSOA = UUID.randomUUID();
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 19);
    private final LeiDaJornada lei = LeiDaJornada.vigente();

    private static HorarioTrabalho horario(String grade, Integer carga, DayOfWeek... dias) {
        Map<DayOfWeek, GradeHoraria> mapa = new EnumMap<>(DayOfWeek.class);
        List<DayOfWeek> lista = dias.length == 0
                ? List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                : List.of(dias);
        lista.forEach(d -> mapa.put(d, GradeHoraria.ler(grade)));
        return new HorarioTrabalho(UUID.randomUUID(), PESSOA, HOJE, 5, mapa, Instant.EPOCH, "teste", carga);
    }

    private static List<String> codigos(List<AvisoLegal> avisos) {
        return avisos.stream().map(AvisoLegal::codigo).toList();
    }

    @Test
    @DisplayName("A carga semanal: a informada ou a soma do horário; a diferença dividida pelos dias com expediente")
    void cargaSemanal() {
        HorarioTrabalho padrao = horario("08:00-12:00 13:00-17:48", null);
        assertThat(padrao.somaSemanalSegundos()).isEqualTo(5 * 31_680);
        assertThat(padrao.cargaSemanalSegundos()).isEqualTo(5 * 31_680);
        assertThat(padrao.diferencaSemanalSegundos()).isZero();
        assertThat(padrao.diferencaPorDiaSegundos()).isZero();

        HorarioTrabalho quarenta = horario("08:00-12:00 13:00-17:00", 42 * 3600);
        assertThat(quarenta.somaSemanalSegundos()).isEqualTo(40 * 3600);
        assertThat(quarenta.diferencaSemanalSegundos()).isEqualTo(2 * 3600);
        assertThat(quarenta.diferencaPorDiaSegundos()).as("2h ÷ 5 dias = 24 min").isEqualTo(24 * 60);

        assertThat(horario("08:00-12:00 13:00-17:48", 42 * 3600).diferencaPorDiaSegundos())
                .as("horário acima da carga: crédito por dia").isEqualTo(-24 * 60);
        assertThatThrownBy(() -> horario("08:00-12:00", 30 * 60)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> horario("08:00-12:00", 81 * 3600)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("As fases: 44h até 15/12/2026, 42h e 2 folgas a partir de 16/12/2026, 40h a partir de 16/12/2027")
    void fases() {
        assertThat(lei.faseEm(HOJE).limiteSemanalSegundos()).isEqualTo(44 * 3600);
        assertThat(lei.faseEm(LocalDate.of(2026, 12, 15)).folgasPorSemana()).isEqualTo(1);
        assertThat(lei.faseEm(LocalDate.of(2026, 12, 16)).limiteSemanalSegundos()).isEqualTo(42 * 3600);
        assertThat(lei.faseEm(LocalDate.of(2026, 12, 16)).folgasPorSemana()).isEqualTo(2);
        assertThat(lei.faseEm(LocalDate.of(2027, 12, 16)).limiteSemanalSegundos()).isEqualTo(40 * 3600);
        assertThat(lei.fasesEntre(HOJE, null)).hasSize(3);
        assertThat(lei.fasesEntre(HOJE, LocalDate.of(2026, 12, 16))).hasSize(1);
    }

    @Test
    @DisplayName("O horário padrão (8h48 x 5) hoje está dentro da lei, mas passa das 42h e das 8h por dia de 16/12/2026")
    void horarioPadraoAvisaDaRegraNova() {
        List<AvisoLegal> avisos = lei.avaliarHorario(horario("08:00-12:00 13:00-17:48", null), HOJE, null);

        assertThat(avisos).noneMatch(a -> a.nivel() == Nivel.ALERTA);
        assertThat(codigos(avisos)).containsExactlyInAnyOrder("SEMANA_ACIMA_DO_LIMITE", "DIA_ACIMA_DA_JORNADA");
        assertThat(avisos).filteredOn(a -> a.codigo().equals("SEMANA_ACIMA_DO_LIMITE")).singleElement()
                .satisfies(a -> {
                    assertThat(a.data()).isEqualTo(LocalDate.of(2026, 12, 16));
                    assertThat(a.mensagem()).contains("44h", "42h", "16/12/2026");
                    assertThat(a.fundamento()).isEqualTo("EC 141/2026");
                });
        assertThat(lei.avaliarHorario(horario("08:00-12:00 13:00-17:48", null), HOJE, LocalDate.of(2026, 12, 1)))
                .as("um horário novo começa antes da regra nova: nada a avisar").isEmpty();
    }

    @Test
    @DisplayName("Carga acima de 44h hoje é alerta; sem folga, intervalo curto, mais de 10h e descanso de menos de 11h também")
    void alertasDoHorario() {
        assertThat(codigos(lei.avaliarHorario(horario("08:00-12:00 13:00-17:48", 46 * 3600), HOJE, null)))
                .contains("SEMANA_ACIMA_DO_LIMITE");
        assertThat(lei.avaliarHorario(horario("08:00-12:00 13:00-17:48", 46 * 3600), HOJE, null))
                .filteredOn(a -> a.data() == null && a.codigo().equals("SEMANA_ACIMA_DO_LIMITE"))
                .singleElement().extracting(AvisoLegal::nivel).isEqualTo(Nivel.ALERTA);

        assertThat(codigos(lei.avaliarHorario(horario("08:00-12:00", null, DayOfWeek.values()), HOJE, null)))
                .contains("HORARIO_SEM_FOLGA");
        assertThat(codigos(lei.avaliarHorario(horario("08:00-12:30 13:00-17:00", null), HOJE, null)))
                .as("8h30 com 30 min de intervalo").contains("INTERVALO_CURTO");
        assertThat(codigos(lei.avaliarHorario(horario("08:00-16:00", null), HOJE, null)))
                .as("8h sem intervalo").contains("INTERVALO_CURTO");
        assertThat(codigos(lei.avaliarHorario(horario("08:00-11:00 14:30-18:00", null), HOJE, null)))
                .contains("INTERVALO_ACIMA_DE_2H");
        assertThat(codigos(lei.avaliarHorario(horario("07:00-12:00 13:00-19:00", null), HOJE, null)))
                .as("11h no dia").contains("DIA_ACIMA_DE_10H");
        assertThat(codigos(lei.avaliarHorario(horario("10:00-12:00 13:00-23:00", null), HOJE, null)))
                .as("sai às 23:00 e entra às 10:00: 11h, no limite").doesNotContain("INTERJORNADA");
        assertThat(codigos(lei.avaliarHorario(horario("06:00-08:00 13:00-23:00", null), HOJE, null)))
                .as("sai às 23:00 e entra às 06:00: 7h").contains("INTERJORNADA");
        assertThat(codigos(lei.avaliarHorario(horario("08:00-13:00", null), HOJE, null)))
                .as("5h corridas: 15 min").contains("INTERVALO_15_MINUTOS");
    }

    @Test
    @DisplayName("Horário que não fecha a carga: hoje, compensar no mês; de 16/12/2026, só com acordo coletivo")
    void compensacao() {
        HorarioTrabalho quarenta = horario("08:00-12:00 13:00-17:00", 42 * 3600);
        List<AvisoLegal> avisos = lei.avaliarHorario(quarenta, HOJE, null);
        assertThat(codigos(avisos)).contains("COMPENSACAO_NO_MES", "COMPENSACAO_COM_ACORDO")
                .doesNotContain("DIA_ACIMA_DA_JORNADA");
        assertThat(avisos).filteredOn(a -> a.codigo().equals("SEMANA_ACIMA_DO_LIMITE")).singleElement()
                .as("a carga de 42h passa das 40h de 16/12/2027").extracting(AvisoLegal::data)
                .isEqualTo(LocalDate.of(2027, 12, 16));
        assertThat(avisos).filteredOn(a -> a.codigo().equals("COMPENSACAO_COM_ACORDO")).singleElement()
                .satisfies(a -> assertThat(a.data()).isEqualTo(LocalDate.of(2026, 12, 16)));

        List<AvisoLegal> depois = lei.avaliarHorario(quarenta, LocalDate.of(2027, 1, 4), null);
        assertThat(codigos(depois)).contains("COMPENSACAO_COM_ACORDO").doesNotContain("COMPENSACAO_NO_MES");
    }

    // ------------------------------------------------------------------ dias trabalhados

    private static LeiDaJornada.DiaTrabalhado dia(LocalDate data, String... batidas) {
        List<LocalTime> horas = new ArrayList<>();
        for (String b : batidas) {
            horas.add(LocalTime.parse(b));
        }
        int trabalhado = 0;
        for (int i = 1; i < horas.size(); i += 2) {
            trabalhado += horas.get(i).toSecondOfDay() - horas.get(i - 1).toSecondOfDay();
        }
        return new LeiDaJornada.DiaTrabalhado(data, horas, trabalhado);
    }

    @Test
    @DisplayName("Dias: mais de 10h, intervalo de menos de 1h acima de 6h e menos de 11h entre um dia e outro")
    void dias() {
        LocalDate seg = LocalDate.of(2026, 10, 5);
        List<AvisoLegal> avisos = lei.avaliarDias(List.of(
                dia(seg, "07:00", "12:00", "13:00", "19:00"),
                dia(seg.plusDays(1), "05:00", "08:00", "08:30", "13:00"),
                dia(seg.plusDays(2), "08:00", "12:00", "13:00", "17:00")), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), HOJE);

        assertThat(avisos).extracting(AvisoLegal::codigo, AvisoLegal::data).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("DIA_ACIMA_DE_10H", seg),
                org.assertj.core.groups.Tuple.tuple("DIA_INTERJORNADA", seg.plusDays(1)),
                org.assertj.core.groups.Tuple.tuple("DIA_INTERVALO_CURTO", seg.plusDays(1)));
    }

    @Test
    @DisplayName("Até 15/12/2026: 7 dias seguidos com trabalho é alerta (sábado e domingo depois da semana inteira)")
    void seteDiasSeguidos() {
        LocalDate seg = LocalDate.of(2026, 10, 5);
        List<LeiDaJornada.DiaTrabalhado> semana = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            semana.add(dia(seg.plusDays(i), "08:00", "12:00", "13:00", "17:00"));
        }
        semana.add(dia(seg.plusDays(5), "08:00", "12:00"));
        assertThat(lei.avaliarDias(semana, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), HOJE))
                .as("só o sábado: tem a folga do domingo").isEmpty();

        semana.add(dia(seg.plusDays(6), "08:00", "10:00"));
        assertThat(lei.avaliarDias(semana, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), HOJE))
                .singleElement().satisfies(a -> {
                    assertThat(a.codigo()).isEqualTo("SEM_FOLGA_7_DIAS");
                    assertThat(a.nivel()).isEqualTo(Nivel.ALERTA);
                    assertThat(a.data()).isEqualTo(seg);
                    assertThat(a.mensagem()).contains("05/10", "11/10");
                });
    }

    @Test
    @DisplayName("De 16/12/2026: semana com 1 folga é aviso, sem folga é alerta, e a média do mês precisa dar 2 por semana")
    void duasFolgas() {
        LocalDate inicio = LocalDate.of(2027, 2, 1); // segunda
        LocalDate fim = LocalDate.of(2027, 2, 28);    // domingo: 4 semanas inteiras
        List<LeiDaJornada.DiaTrabalhado> dias = new ArrayList<>();
        for (LocalDate d = inicio; !d.isAfter(fim); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                dias.add(dia(d, "08:00", "12:00", "13:00", "17:00"));
            }
        }
        LocalDate hoje = LocalDate.of(2027, 3, 10);
        assertThat(lei.avaliarDias(dias, inicio, fim, hoje)).as("5x2: tudo certo").isEmpty();

        dias.add(dia(LocalDate.of(2027, 2, 6), "08:00", "12:00")); // sábado da 1ª semana
        assertThat(lei.avaliarDias(dias, inicio, fim, hoje)).extracting(AvisoLegal::codigo)
                .as("uma semana com 1 folga, média 1,75 no mês").containsExactlyInAnyOrder("SEMANA_COM_UMA_FOLGA", "MEDIA_DE_FOLGAS");

        dias.add(dia(LocalDate.of(2027, 2, 7), "08:00", "12:00")); // e o domingo
        assertThat(lei.avaliarDias(dias, inicio, fim, hoje)).extracting(AvisoLegal::codigo)
                .contains("SEMANA_SEM_FOLGA").doesNotContain("SEM_FOLGA_7_DIAS");
    }

    @Test
    @DisplayName("Os dias que ainda não aconteceram não contam")
    void soAteHoje() {
        LocalDate seg = LocalDate.of(2026, 10, 19);
        assertThat(lei.avaliarDias(List.of(dia(seg.plusDays(3), "07:00", "19:00")), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), seg)).isEmpty();
    }

    @Test
    @DisplayName("Durações nas mensagens: 8h48, 10h, 0h24")
    void duracao() {
        assertThat(LeiDaJornada.duracao(31_680)).isEqualTo("8h48");
        assertThat(LeiDaJornada.duracao(36_000)).isEqualTo("10h");
        assertThat(LeiDaJornada.duracao(-1_440)).isEqualTo("0h24");
    }
}
