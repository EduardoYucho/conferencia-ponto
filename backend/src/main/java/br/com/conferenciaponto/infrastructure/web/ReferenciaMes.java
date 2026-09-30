package br.com.conferenciaponto.infrastructure.web;

import java.time.Clock;
import java.time.YearMonth;

/** Resolve ano/mês opcionais da query string, usando o mês corrente como padrão. */
final class ReferenciaMes {

    private ReferenciaMes() {
    }

    static YearMonth resolver(Integer ano, Integer mes, Clock clock) {
        YearMonth atual = YearMonth.now(clock);
        return YearMonth.of(
                ano != null ? ano : atual.getYear(),
                mes != null ? mes : atual.getMonthValue()); // DateTimeException -> 400
    }
}
