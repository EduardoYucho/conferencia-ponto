package br.com.conferenciaponto.domain.port;

import java.time.LocalDate;

/** Consulta se a data está coberta por férias, atestado, licença ou folga. */
@FunctionalInterface
public interface CalendarioAusencias {

    CalendarioAusencias NENHUMA = data -> false;

    boolean isAusencia(LocalDate data);
}
