package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrarBatidaUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria repository = new RegistroJornadaRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final ClassificadorDiaService classificador = new ClassificadorDiaService(data -> false);

    private RegistrarBatidaUseCase useCaseEm(String horaAtual) {
        Clock clock = Clock.fixed(ZonedDateTime.of(DIA, LocalTime.parse(horaAtual), SP).toInstant(), SP);
        return new RegistrarBatidaUseCase(repository, classificador, motor, evento -> { }, clock);
    }

    @Test
    @DisplayName("Fluxo real de 28/09/2026: três batidas, jornada em andamento, depois fechamento pelo relógio")
    void fluxoCompleto28Setembro() {
        RegistrarBatidaUseCase useCase = useCaseEm("13:30:00");
        useCase.executar(DIA, LocalTime.parse("08:02:31"));
        useCase.executar(DIA, LocalTime.parse("12:00:15"));
        RegistroJornadaView emAndamento = useCase.executar(DIA, LocalTime.parse("12:59:59"));

        assertThat(emAndamento.status()).isEqualTo(StatusJornada.EM_ANDAMENTO);
        assertThat(emAndamento.segundosTrabalhados()).isEqualTo(14_264); // 08:02:31 → 12:00:15, bruto
        assertThat(emAndamento.saldoDiarioSegundos()).isNull();
        assertThat(emAndamento.marcacoes().get(TipoBatida.ENTRADA_2.ordinal()).considerado())
                .isEqualTo(LocalTime.of(13, 0));

        // Sem horário informado: usa o relógio do servidor (17:50:10 -> 17:48 pela tolerância)
        RegistroJornadaView fechada = useCaseEm("17:50:10").executar(null, null);

        assertThat(fechada.data()).isEqualTo(DIA);
        assertThat(fechada.status()).isEqualTo(StatusJornada.FECHADA);
        assertThat(fechada.segundosTrabalhados()).isEqualTo(31_675);
        assertThat(fechada.saldoDiarioSegundos()).isZero(); // todos os desvios tolerados
        assertThat(repository.quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejeita batida no futuro")
    void rejeitaFuturo() {
        assertThatThrownBy(() -> useCaseEm("13:30:00").executar(DIA, LocalTime.of(23, 0)))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("BATIDA_NO_FUTURO");
    }

    @Test
    @DisplayName("Aceita até 6 batidas (3 intervalos) e rejeita a 7ª")
    void rejeitaSetimaBatida() {
        RegistrarBatidaUseCase useCase = useCaseEm("20:00:00");
        useCase.executar(DIA, LocalTime.parse("08:00"));
        useCase.executar(DIA, LocalTime.parse("12:00"));
        useCase.executar(DIA, LocalTime.parse("13:00"));
        useCase.executar(DIA, LocalTime.parse("17:48"));
        useCase.executar(DIA, LocalTime.parse("18:30"));
        useCase.executar(DIA, LocalTime.parse("19:00"));

        assertThatThrownBy(() -> useCase.executar(DIA, LocalTime.parse("19:30")))
                .extracting("codigo").isEqualTo("JORNADA_COMPLETA");
    }
}
