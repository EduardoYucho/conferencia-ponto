package br.com.conferenciaponto.application.evento;

import br.com.conferenciaponto.application.view.CicloBancoView;

/** O ciclo aberto mudou (fechamento, fechamento desfeito ou período corrigido). */
public record CicloAtualizadoEvento(CicloBancoView cicloAberto, String descricao) {
}
