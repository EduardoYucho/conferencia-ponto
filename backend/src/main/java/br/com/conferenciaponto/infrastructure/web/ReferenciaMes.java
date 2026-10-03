package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.Clock;
import java.time.YearMonth;

/** Resolve ano/mês opcionais da query string, usando o mês corrente como padrão. */
final class ReferenciaMes {

    /** Faixa de anos aceita nas consultas (evita telas vazias por erro de digitação, como o ano 20226). */
    static final int ANO_MINIMO = 2000;
    static final int ANOS_A_FRENTE = 5;

    private ReferenciaMes() {
    }

    static YearMonth resolver(Integer ano, Integer mes, Clock clock) {
        YearMonth atual = YearMonth.now(clock);
        int anoPedido = ano != null ? ano : atual.getYear();
        int mesPedido = mes != null ? mes : atual.getMonthValue();
        if (mesPedido < 1 || mesPedido > 12) {
            throw new RegraNegocioException("MES_INVALIDO", "Mês inválido: informe de 1 a 12.");
        }
        if (anoPedido < ANO_MINIMO || anoPedido > atual.getYear() + ANOS_A_FRENTE) {
            throw new RegraNegocioException("ANO_INVALIDO", "Ano inválido: informe um ano entre %d e %d."
                    .formatted(ANO_MINIMO, atual.getYear() + ANOS_A_FRENTE));
        }
        return YearMonth.of(anoPedido, mesPedido);
    }
}
