package br.com.expedio.application.evento;

import br.com.expedio.domain.model.Notificacao;

public record NotificacaoCriadaEvento(Notificacao notificacao, int naoLidas) {
}
