package br.com.conferenciaponto.application.evento;

import br.com.conferenciaponto.domain.model.Notificacao;

public record NotificacaoCriadaEvento(Notificacao notificacao, int naoLidas) {
}
