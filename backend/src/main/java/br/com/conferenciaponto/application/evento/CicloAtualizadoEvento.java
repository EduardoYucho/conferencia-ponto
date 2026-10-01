package br.com.conferenciaponto.application.evento;

import br.com.conferenciaponto.application.view.CicloBancoView;

import java.util.UUID;

/** O ciclo aberto de um usuário mudou (fechamento, fechamento desfeito ou período corrigido). */
public record CicloAtualizadoEvento(UUID usuarioId, CicloBancoView cicloAberto, String descricao) {
}
