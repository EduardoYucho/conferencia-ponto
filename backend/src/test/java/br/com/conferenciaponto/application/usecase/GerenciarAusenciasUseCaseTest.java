package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarAusenciasUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate SEG_02_03 = LocalDate.of(2026, 3, 2);
    private static final LocalDate SEX_13_03 = LocalDate.of(2026, 3, 13);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final AusenciaRepositoryEmMemoria ausencias = new AusenciaRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final ClassificadorDiaService classificador = new ClassificadorDiaService(data -> false, ausencias);
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, SP).toInstant(), SP);

    private final GerenciarAusenciasUseCase useCase =
            new GerenciarAusenciasUseCase(ausencias, registros, classificador, motor, eventos::add, clock);

    @Test
    @DisplayName("Férias zeram a jornada base: dia útil registrado vira AUSENCIA e o trabalhado vira crédito")
    void cadastroReclassificaDiasRegistrados() {
        RegistroJornada dia = RegistroJornada.novo(SEG_02_03, TipoDia.UTIL);
        dia.incluirBatida(LocalTime.of(9, 0), motor);
        dia.incluirBatida(LocalTime.of(10, 0), motor);
        registros.salvar(dia);
        assertThat(dia.getSaldoDiarioSegundos()).isEqualTo(3_600 - 31_680);

        Ausencia ferias = useCase.cadastrar(SEG_02_03, SEX_13_03, TipoAusencia.FERIAS, "Férias 2026", "eduardo");

        assertThat(ferias.rotulo()).isEqualTo("Férias · Férias 2026");
        RegistroJornada depois = registros.buscarPorData(SEG_02_03).orElseThrow();
        assertThat(depois.getTipoDia()).isEqualTo(TipoDia.AUSENCIA);
        assertThat(depois.getJornadaPrevistaSegundos()).isZero();
        assertThat(depois.getSaldoDiarioSegundos()).isEqualTo(3_600);
        assertThat(eventos).filteredOn(JornadaAtualizadaEvento.class::isInstance).singleElement()
                .isInstanceOfSatisfying(JornadaAtualizadaEvento.class,
                        e -> assertThat(e.origem()).isEqualTo(OrigemAtualizacao.AUSENCIA));
    }

    @Test
    @DisplayName("Fim de semana dentro das férias continua FIM_DE_SEMANA; excluir devolve o dia útil")
    void exclusaoDevolveDiaUtil() {
        RegistroJornada terca = RegistroJornada.novo(SEG_02_03.plusDays(1), TipoDia.UTIL);
        terca.incluirBatida(LocalTime.of(8, 0), motor);
        registros.salvar(terca);
        Ausencia ferias = useCase.cadastrar(SEG_02_03, SEX_13_03, TipoAusencia.FERIAS, null, "eduardo");

        assertThat(classificador.classificar(LocalDate.of(2026, 3, 7))).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(classificador.classificar(SEG_02_03)).isEqualTo(TipoDia.AUSENCIA);
        assertThat(registros.buscarPorData(SEG_02_03.plusDays(1)).orElseThrow().getStatus())
                .isEqualTo(StatusJornada.EM_ANDAMENTO);

        useCase.excluir(ferias.id());

        assertThat(ausencias.todas()).isEmpty();
        assertThat(registros.buscarPorData(SEG_02_03.plusDays(1)).orElseThrow().getTipoDia()).isEqualTo(TipoDia.UTIL);
        assertThat(eventos).filteredOn(JornadaAtualizadaEvento.class::isInstance).hasSize(2);
    }

    @Test
    @DisplayName("Períodos sobrepostos são recusados")
    void sobreposicao() {
        useCase.cadastrar(SEG_02_03, SEX_13_03, TipoAusencia.FERIAS, null, "eduardo");

        assertThatThrownBy(() -> useCase.cadastrar(SEX_13_03, SEX_13_03, TipoAusencia.ATESTADO, null, "eduardo"))
                .isInstanceOf(ConflitoException.class)
                .hasMessageContaining("férias de 02/03/2026 a 13/03/2026");
    }

    @Test
    @DisplayName("Datas invertidas são recusadas")
    void datasInvertidas() {
        assertThatThrownBy(() -> useCase.cadastrar(SEX_13_03, SEG_02_03, TipoAusencia.LICENCA, null, "eduardo"))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("posterior ao início");
    }

    @Test
    @DisplayName("Dias de férias vindos do RH, um a um, viram um único período (e unem dois períodos vizinhos)")
    void registrarDiaJuntaPeriodos() {
        for (int d = 2; d <= 6; d++) {
            useCase.registrarDia(LocalDate.of(2026, 3, d), TipoAusencia.FERIAS, "conforme RH", "eduardo");
        }
        for (int d = 10; d <= 13; d++) {
            useCase.registrarDia(LocalDate.of(2026, 3, d), TipoAusencia.FERIAS, "conforme RH", "eduardo");
        }
        assertThat(ausencias.todas()).hasSize(2);

        // fim de semana e segunda que faltavam: tudo vira um período só
        useCase.registrarDia(LocalDate.of(2026, 3, 7), TipoAusencia.FERIAS, "conforme RH", "eduardo");
        useCase.registrarDia(LocalDate.of(2026, 3, 8), TipoAusencia.FERIAS, "conforme RH", "eduardo");
        useCase.registrarDia(LocalDate.of(2026, 3, 9), TipoAusencia.FERIAS, "conforme RH", "eduardo");

        assertThat(ausencias.todas()).singleElement().satisfies(a -> {
            assertThat(a.dataInicio()).isEqualTo(SEG_02_03);
            assertThat(a.dataFim()).isEqualTo(SEX_13_03);
        });
        // repetir um dia já coberto não duplica nada
        useCase.registrarDia(LocalDate.of(2026, 3, 4), TipoAusencia.FERIAS, null, "eduardo");
        assertThat(ausencias.todas()).hasSize(1);
    }

    @Test
    @DisplayName("Folga encostada em férias é um período separado (tipos diferentes não se juntam)")
    void tiposDiferentesNaoSeJuntam() {
        useCase.registrarDia(SEG_02_03, TipoAusencia.FERIAS, null, "eduardo");
        useCase.registrarDia(SEG_02_03.plusDays(1), TipoAusencia.FOLGA, "aniversário", "eduardo");

        assertThat(ausencias.todas()).extracting(Ausencia::tipo)
                .containsExactly(TipoAusencia.FERIAS, TipoAusencia.FOLGA);
        assertThatThrownBy(() -> useCase.registrarDia(SEG_02_03, TipoAusencia.ATESTADO, null, "eduardo"))
                .isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("Abono (outra justificativa): exige o motivo e isenta o dia como as demais ausências")
    void abonoExigeJustificativa() {
        assertThatThrownBy(() -> useCase.cadastrar(SEG_02_03, SEG_02_03, TipoAusencia.ABONO, "  ", "eduardo"))
                .isInstanceOf(br.com.conferenciaponto.domain.exception.RegraNegocioException.class)
                .hasMessageContaining("justificativa do abono");

        Ausencia abono = useCase.cadastrar(SEG_02_03, SEG_02_03, TipoAusencia.ABONO, "Doação de sangue", "eduardo");

        assertThat(abono.rotulo()).isEqualTo("Abono · Doação de sangue");
        assertThat(classificador.classificar(SEG_02_03)).isEqualTo(TipoDia.AUSENCIA);
    }
}
