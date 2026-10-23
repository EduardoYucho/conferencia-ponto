package br.com.conferenciaponto.application.evento;

import java.time.YearMonth;
import java.util.UUID;

/** O mês de uma pessoa foi conferido ou a conferência foi reaberta. */
public record ConferenciaAlteradaEvento(UUID usuarioId, YearMonth mes, String descricao) {
}
