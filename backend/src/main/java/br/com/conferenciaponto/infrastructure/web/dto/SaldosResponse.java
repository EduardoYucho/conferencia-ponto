package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.application.view.ResumoSaldosView;

import java.util.List;

public record SaldosResponse(
        int ano,
        int mes,
        int saldoMensalSegundos,
        int saldoAnualAcumuladoSegundos,
        List<ResumoMensalResponse> meses,
        CicloBancoResponse ciclo) {

    /** @param ciclo ciclo aberto do banco de horas (o saldo "de verdade", que o RH zera a cada fechamento) */
    public static SaldosResponse de(ResumoSaldosView v, CicloBancoView ciclo) {
        return new SaldosResponse(
                v.referencia().getYear(),
                v.referencia().getMonthValue(),
                v.saldoMensalSegundos(),
                v.saldoAnualAcumuladoSegundos(),
                v.meses().stream().map(ResumoMensalResponse::de).toList(),
                ciclo == null ? null : CicloBancoResponse.de(ciclo));
    }
}
