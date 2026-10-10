package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import java.util.UUID;

/** Mudou algo no processamento de um atendimento: vai pelo SSE do gerador só para o dono. */
public record ProgressoAtualizado(UUID usuarioId, ProgressoView progresso) {
}
