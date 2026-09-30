package br.com.conferenciaponto.infrastructure.web.dto;

/** Payload do evento SSE {@code conciliacao-atualizada}. */
public record ConciliacaoEventoResponse(String descricao, int pendentes) {
}
