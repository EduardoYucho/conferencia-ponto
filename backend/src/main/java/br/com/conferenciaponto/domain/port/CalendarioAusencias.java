package br.com.conferenciaponto.domain.port;

import java.time.LocalDate;
import java.util.UUID;

/** Consulta se a data está coberta por férias, atestado, licença ou folga. */
@FunctionalInterface
public interface CalendarioAusencias {

    CalendarioAusencias NENHUMA = (usuarioId, data) -> false;

    boolean isAusencia(UUID usuarioId, LocalDate data);
}
