package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.view.ResumoSaldosView;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;

class ConsultarJornadaUseCaseTest {

    private final RegistroJornadaRepositoryEmMemoria repository = new RegistroJornadaRepositoryEmMemoria();
    private final LancamentoBancoRepositoryEmMemoria lancamentos = new LancamentoBancoRepositoryEmMemoria();
    private final ConsultarJornadaUseCase useCase =
            new ConsultarJornadaUseCase(repository, Fixtures.regras(new ClassificadorDiaService(data -> false)), new AusenciaRepositoryEmMemoria(),
                    data -> false, lancamentos, new ConsolidacaoBancoHoras(repository, lancamentos));

    @Test
    @DisplayName("Saldo anual acumulado: meses sem registro herdam o acumulado anterior")
    void acumuladoAnual() {
        repository.definirConsolidacao(List.of(
                new SaldoMensal(2026, 8, 20, 0, 632_400, 633_600, -1_200, -1_200),
                new SaldoMensal(2026, 9, 21, 1, 678_600, 665_280, 13_320, 12_120)));

        ResumoSaldosView setembro = useCase.saldos(USUARIO, YearMonth.of(2026, 9));
        assertThat(setembro.saldoMensalSegundos()).isEqualTo(13_320);
        assertThat(setembro.saldoAnualAcumuladoSegundos()).isEqualTo(12_120);
        assertThat(setembro.meses()).hasSize(12);

        ResumoSaldosView outubro = useCase.saldos(USUARIO, YearMonth.of(2026, 10));
        assertThat(outubro.saldoMensalSegundos()).isZero();
        assertThat(outubro.saldoAnualAcumuladoSegundos()).isEqualTo(12_120);

        ResumoSaldosView janeiro = useCase.saldos(USUARIO, YearMonth.of(2026, 1));
        assertThat(janeiro.saldoAnualAcumuladoSegundos()).isZero();
    }

    @Test
    @DisplayName("Lançamentos no banco entram no saldo do mês e no acumulado (inclusive num mês sem jornada)")
    void lancamentosNoSaldo() {
        repository.definirConsolidacao(List.of(new SaldoMensal(2026, 9, 21, 0, 678_600, 665_280, 13_320, 13_320)));
        lancamentos.salvar(LancamentoBanco.novo(USUARIO, LocalDate.of(2026, 9, 26), -14_400, "Compensação", "eduardo", Instant.EPOCH));
        lancamentos.salvar(LancamentoBanco.novo(USUARIO, LocalDate.of(2026, 11, 2), -3_600, "Saída antecipada", "eduardo", Instant.EPOCH));

        ResumoSaldosView setembro = useCase.saldos(USUARIO, YearMonth.of(2026, 9));
        assertThat(setembro.saldoMensalSegundos()).isEqualTo(13_320 - 14_400);
        assertThat(setembro.meses().get(8).segundosLancados()).isEqualTo(-14_400);
        assertThat(setembro.meses().get(8).saldoJornadasSegundos()).isEqualTo(13_320);

        ResumoSaldosView novembro = useCase.saldos(USUARIO, YearMonth.of(2026, 11));
        assertThat(novembro.saldoMensalSegundos()).isEqualTo(-3_600);
        assertThat(novembro.saldoAnualAcumuladoSegundos()).isEqualTo(13_320 - 14_400 - 3_600);
        assertThat(useCase.mes(USUARIO, YearMonth.of(2026, 11)).lancamentos()).singleElement()
                .satisfies(l -> assertThat(l.descricao()).isEqualTo("Saída antecipada"));
    }
}
