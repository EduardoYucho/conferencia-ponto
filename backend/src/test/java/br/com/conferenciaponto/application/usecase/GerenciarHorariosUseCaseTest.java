package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static br.com.conferenciaponto.application.usecase.Fixtures.OUTRO;
import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarHorariosUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate SEG_21 = LocalDate.of(2026, 9, 21);
    private static final LocalDate SAB_26 = LocalDate.of(2026, 9, 26);
    private static final LocalDate SEG_28 = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final HorarioTrabalhoRepositoryEmMemoria horarios = new HorarioTrabalhoRepositoryEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(data -> false), horarios);
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, SP).toInstant(), SP);
    private final GerenciarHorariosUseCase useCase = new GerenciarHorariosUseCase(horarios, ciclos, regras,
            new RecalcularJornadasUseCase(registros, regras), eventos::add, clock);

    private RegistroJornada dia(java.util.UUID usuario, LocalDate data, String... batidas) {
        RegistroJornada r = RegistroJornada.novo(usuario, data, regras.classificar(usuario, data));
        for (String b : batidas) {
            r.incluirBatida(LocalTime.parse(b), regras.motor(usuario, data));
        }
        registros.salvar(r);
        return r;
    }

    /** Segunda a sexta 07:00–11:00 e 12:00–16:00 (8 h) e sábado 08:00–12:00. */
    private static Map<DayOfWeek, GradeHoraria> novoHorario() {
        Map<DayOfWeek, GradeHoraria> dias = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {
            dias.put(d, GradeHoraria.ler("07:00-11:00 12:00-16:00"));
        }
        dias.put(DayOfWeek.SATURDAY, GradeHoraria.ler("08:00-12:00"));
        return dias;
    }

    @Test
    @DisplayName("Sem horário cadastrado vale o padrão (seg–sex 08:00–12:00 e 13:00–17:48)")
    void horarioPadrao() {
        List<HorarioTrabalho> lista = useCase.listar(USUARIO);
        assertThat(lista).singleElement().satisfies(h -> {
            assertThat(h.vigenteDesde()).isEqualTo(HorarioTrabalho.DESDE_SEMPRE);
            assertThat(h.previstoSegundos(SEG_28)).isEqualTo(31_680);
            assertThat(h.temExpediente(SAB_26)).isFalse();
        });
    }

    @Test
    @DisplayName("Novo horário a partir de uma data: só os dias dali em diante são recalculados (sábado vira dia útil)")
    void novoHorarioRecalculaDaVigenciaEmDiante() {
        dia(USUARIO, SEG_21, "08:00", "12:00", "13:00", "17:48");
        dia(USUARIO, SAB_26, "08:00", "12:00");
        dia(USUARIO, SEG_28, "08:00", "12:00", "13:00", "17:48");
        RegistroJornada doOutro = dia(OUTRO, SEG_28, "08:00", "12:00", "13:00", "17:48");
        assertThat(registros.buscarPorData(USUARIO, SAB_26).orElseThrow().getSaldoDiarioSegundos()).isEqualTo(4 * 3600);

        GerenciarHorariosUseCase.Alteracao a = useCase.salvar(USUARIO, LocalDate.of(2026, 9, 22), 5, novoHorario(),
                "eduardo");

        assertThat(a.diasRecalculados()).isEqualTo(2);
        assertThat(a.vigencias()).extracting(HorarioTrabalho::vigenteDesde)
                .containsExactly(HorarioTrabalho.DESDE_SEMPRE, LocalDate.of(2026, 9, 22));
        // antes da vigência: continua com o horário antigo
        assertThat(registros.buscarPorData(USUARIO, SEG_21).orElseThrow().getSaldoDiarioSegundos()).isZero();
        // sábado agora é útil (4 h previstas, 4 h trabalhadas)
        RegistroJornada sabado = registros.buscarPorData(USUARIO, SAB_26).orElseThrow();
        assertThat(sabado.getTipoDia()).isEqualTo(TipoDia.UTIL);
        assertThat(sabado.getSaldoDiarioSegundos()).isZero();
        // segunda: trabalhou 08:48 com previsão de 08:00
        assertThat(registros.buscarPorData(USUARIO, SEG_28).orElseThrow().getSaldoDiarioSegundos()).isEqualTo(48 * 60);
        // o horário de outra pessoa não muda
        assertThat(registros.buscarPorData(OUTRO, SEG_28).orElseThrow().getSaldoDiarioSegundos())
                .isEqualTo(doOutro.getSaldoDiarioSegundos()).isZero();
        assertThat(eventos).singleElement().isInstanceOf(CalendarioAlteradoEvento.class);
    }

    @Test
    @DisplayName("Remover a vigência devolve os dias ao horário anterior; a primeira não pode ser removida")
    void removerVigencia() {
        dia(USUARIO, SAB_26, "08:00", "12:00");
        GerenciarHorariosUseCase.Alteracao a = useCase.salvar(USUARIO, LocalDate.of(2026, 9, 22), 5, novoHorario(),
                "eduardo");
        HorarioTrabalho novo = a.vigencias().get(1);

        useCase.excluir(USUARIO, novo.id());

        assertThat(registros.buscarPorData(USUARIO, SAB_26).orElseThrow().getTipoDia()).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThatThrownBy(() -> useCase.excluir(USUARIO, a.vigencias().get(0).id()))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("primeiro horário");
    }

    @Test
    @DisplayName("Mesma data substitui a vigência; horário em ciclo do banco já fechado é recusado")
    void regrasDaVigencia() {
        useCase.salvar(USUARIO, LocalDate.of(2026, 9, 22), 5, novoHorario(), "eduardo");
        GerenciarHorariosUseCase.Alteracao a = useCase.salvar(USUARIO, LocalDate.of(2026, 9, 22), 10, novoHorario(),
                "eduardo");
        assertThat(a.vigencias()).hasSize(2).last().extracting(HorarioTrabalho::toleranciaMinutos).isEqualTo(10);

        CicloBanco fechado = CicloBanco.abrir(USUARIO, LocalDate.of(2026, 3, 25), 6, Instant.EPOCH)
                .fechar(LocalDate.of(2026, 9, 24), 0, "eduardo", null, LocalDate.of(2026, 9, 25), Instant.EPOCH);
        ciclos.salvar(fechado);
        assertThatThrownBy(() -> useCase.salvar(USUARIO, LocalDate.of(2026, 9, 20), 5, novoHorario(), "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("25/09/2026");
        assertThatThrownBy(() -> useCase.salvar(USUARIO, LocalDate.of(2026, 10, 1), 5, Map.of(), "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("pelo menos um dia");
    }

    @Test
    @DisplayName("Grade: até 3 períodos, em ordem; texto vazio = sem expediente")
    void grade() {
        assertThat(GradeHoraria.ler("8:00-12:00, 13:00-17:48").cargaHorariaSegundos()).isEqualTo(31_680);
        assertThat(GradeHoraria.ler("06:00-10:00 10:30-12:00 13:00-15:00").periodos()).hasSize(3);
        assertThat(GradeHoraria.ler("  ")).isNull();
        assertThatThrownBy(() -> GradeHoraria.ler("13:00-17:00 08:00-12:00")).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> GradeHoraria.ler("08:00-12:00 13:00-14:00 15:00-16:00 17:00-18:00"))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> GradeHoraria.ler("8h às 12h")).isInstanceOf(RegraNegocioException.class);
    }
}
