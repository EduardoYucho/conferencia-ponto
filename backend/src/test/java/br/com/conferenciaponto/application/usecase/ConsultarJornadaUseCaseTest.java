package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.ResumoSaldosView;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultarJornadaUseCaseTest {

    private final RegistroJornadaRepositoryEmMemoria repository = new RegistroJornadaRepositoryEmMemoria();
    private final ConsultarJornadaUseCase useCase =
            new ConsultarJornadaUseCase(repository, new MotorCalculoJornadaService(), new AusenciaRepositoryEmMemoria(),
                    data -> false);

    @Test
    @DisplayName("Saldo anual acumulado: meses sem registro herdam o acumulado anterior")
    void acumuladoAnual() {
        repository.definirConsolidacao(List.of(
                new SaldoMensal(2026, 8, 20, 0, 632_400, 633_600, -1_200, -1_200),
                new SaldoMensal(2026, 9, 21, 1, 678_600, 665_280, 13_320, 12_120)));

        ResumoSaldosView setembro = useCase.saldos(YearMonth.of(2026, 9));
        assertThat(setembro.saldoMensalSegundos()).isEqualTo(13_320);
        assertThat(setembro.saldoAnualAcumuladoSegundos()).isEqualTo(12_120);
        assertThat(setembro.meses()).hasSize(12);

        ResumoSaldosView outubro = useCase.saldos(YearMonth.of(2026, 10));
        assertThat(outubro.saldoMensalSegundos()).isZero();
        assertThat(outubro.saldoAnualAcumuladoSegundos()).isEqualTo(12_120);

        ResumoSaldosView janeiro = useCase.saldos(YearMonth.of(2026, 1));
        assertThat(janeiro.saldoAnualAcumuladoSegundos()).isZero();
    }
}
