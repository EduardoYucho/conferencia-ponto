package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.SaldoMensal;

public record ResumoMensalResponse(
        int ano,
        int mes,
        int diasRegistrados,
        int diasEmAberto,
        int segundosTrabalhados,
        int segundosPrevistos,
        int saldoMensalSegundos,
        int saldoAnualAcumuladoSegundos,
        int segundosLancados) {

    public static ResumoMensalResponse de(SaldoMensal s) {
        return new ResumoMensalResponse(s.ano(), s.mes(), s.diasRegistrados(), s.diasEmAberto(),
                s.segundosTrabalhados(), s.segundosPrevistos(), s.saldoMensalSegundos(),
                s.saldoAnualAcumuladoSegundos(), s.segundosLancados());
    }
}
