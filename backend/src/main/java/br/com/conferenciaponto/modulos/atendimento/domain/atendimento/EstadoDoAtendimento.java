package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.util.UUID;

/** Dono e situação de um atendimento (o que a fila e o acompanhamento precisam saber). */
public record EstadoDoAtendimento(UUID id, UUID usuarioId, SituacaoDoAtendimento situacao, String motivoPausa) {
}
