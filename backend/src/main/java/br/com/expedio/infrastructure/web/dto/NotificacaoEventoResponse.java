package br.com.expedio.infrastructure.web.dto;

import br.com.expedio.application.evento.NotificacaoCriadaEvento;

/** Payload do evento SSE {@code notificacao}. */
public record NotificacaoEventoResponse(NotificacaoResponse notificacao, int naoLidas) {

    public static NotificacaoEventoResponse de(NotificacaoCriadaEvento evento) {
        return new NotificacaoEventoResponse(NotificacaoResponse.de(evento.notificacao()), evento.naoLidas());
    }
}
