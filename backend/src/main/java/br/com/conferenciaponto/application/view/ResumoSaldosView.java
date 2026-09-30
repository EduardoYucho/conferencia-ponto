package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.YearMonth;
import java.util.List;

/**
 * Saldos consolidados para o dashboard.
 *
 * @param saldoAnualAcumuladoSegundos soma dos saldos de janeiro até o mês de referência
 * @param meses                      os 12 meses do ano (meses sem registro vêm zerados)
 */
public record ResumoSaldosView(
        YearMonth referencia,
        int saldoMensalSegundos,
        int saldoAnualAcumuladoSegundos,
        List<SaldoMensal> meses) {
}
