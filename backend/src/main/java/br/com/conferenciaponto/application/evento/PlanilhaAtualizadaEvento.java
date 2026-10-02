package br.com.conferenciaponto.application.evento;

import br.com.conferenciaponto.application.view.EstadoPlanilhaView;

import java.util.UUID;

/** A planilha do Google de um usuário foi gravada, falhou ao gravar, foi vinculada ou desvinculada. */
public record PlanilhaAtualizadaEvento(UUID usuarioId, EstadoPlanilhaView estado) {
}
