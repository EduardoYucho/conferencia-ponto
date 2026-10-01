package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Intervalo;
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
import java.util.List;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LancarRegistroManualUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate SABADO = LocalDate.of(2026, 9, 26);
    private static final LocalDate SEGUNDA = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria repository = new RegistroJornadaRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final ClassificadorDiaService classificador = new ClassificadorDiaService(data -> false);
    private final Clock clock = Clock.fixed(ZonedDateTime.of(SEGUNDA, LocalTime.of(18, 0), SP).toInstant(), SP);

    private final LancarRegistroManualUseCase useCase =
            new LancarRegistroManualUseCase(repository, Fixtures.regras(classificador), evento -> { }, clock);

    private static Intervalo intervalo(String entrada, String saida) {
        return new Intervalo(LocalTime.parse(entrada), LocalTime.parse(saida));
    }

    @Test
    @DisplayName("Sábado: 100% das horas viram crédito e o registro é marcado como manual")
    void sabadoTudoCredito() {
        RegistroJornadaView v = useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:30")));

        assertThat(v.tipoDia()).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(v.registroManual()).isTrue();
        assertThat(v.jornadaPrevistaSegundos()).isZero();
        assertThat(v.saldoDiarioSegundos()).isEqualTo(210 * 60);
    }

    @Test
    @DisplayName("Relançar a mesma data substitui o lançamento manual anterior")
    void relancamentoSubstitui() {
        useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:30")));
        RegistroJornadaView v = useCase.executar(USUARIO, SABADO,
                List.of(intervalo("08:00", "12:00"), intervalo("13:00", "14:00")));

        assertThat(v.saldoDiarioSegundos()).isEqualTo(300 * 60);
        assertThat(repository.quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dia útil não aceita lançamento manual")
    void rejeitaDiaUtil() {
        assertThatThrownBy(() -> useCase.executar(USUARIO, SEGUNDA, List.of(intervalo("08:00", "12:00"))))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("LANCAMENTO_MANUAL_DIA_UTIL");
    }

    @Test
    @DisplayName("Não sobrescreve batidas de relógio já existentes")
    void naoSobrescreveRelogio() {
        new RegistrarBatidaUseCase(repository, Fixtures.regras(classificador), evento -> { }, clock)
                .executar(USUARIO, SABADO, LocalTime.of(9, 0));

        assertThatThrownBy(() -> useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:00"))))
                .isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("Dia útil já batido: a regra de dia útil prevalece sobre o conflito")
    void diaUtilComBatidasRetornaErroDeDiaUtil() {
        new RegistrarBatidaUseCase(repository, Fixtures.regras(classificador), evento -> { }, clock)
                .executar(USUARIO, SEGUNDA, LocalTime.of(8, 0));

        assertThatThrownBy(() -> useCase.executar(USUARIO, SEGUNDA, List.of(intervalo("09:00", "11:00"))))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("LANCAMENTO_MANUAL_DIA_UTIL");
    }

    @Test
    @DisplayName("Rejeita data futura")
    void rejeitaFuturo() {
        assertThatThrownBy(() -> useCase.executar(USUARIO, LocalDate.of(2026, 10, 3), List.of(intervalo("09:00", "12:00"))))
                .extracting("codigo").isEqualTo("LANCAMENTO_NO_FUTURO");
    }
}
