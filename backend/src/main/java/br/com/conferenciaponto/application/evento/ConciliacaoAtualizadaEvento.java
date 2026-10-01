package br.com.conferenciaponto.application.evento;

import java.util.UUID;

/** Algo mudou na conciliação com o RH (relatório conferido, divergência resolvida...). */
public record ConciliacaoAtualizadaEvento(UUID usuarioId, String descricao, int pendentes) {
}
