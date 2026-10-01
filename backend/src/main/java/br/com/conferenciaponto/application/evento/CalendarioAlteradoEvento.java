package br.com.conferenciaponto.application.evento;

import java.util.UUID;

import java.time.LocalDate;

/** Feriados/ausências do período mudaram (dias sem registro também mudam de tipo). */
/** @param usuarioId dono das ausências; {@code null} = feriado, vale para todos */
public record CalendarioAlteradoEvento(UUID usuarioId, LocalDate inicio, LocalDate fim) {
}
