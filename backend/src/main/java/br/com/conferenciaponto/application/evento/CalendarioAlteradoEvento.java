package br.com.conferenciaponto.application.evento;

import java.time.LocalDate;

/** Feriados/ausências do período mudaram (dias sem registro também mudam de tipo). */
public record CalendarioAlteradoEvento(LocalDate inicio, LocalDate fim) {
}
