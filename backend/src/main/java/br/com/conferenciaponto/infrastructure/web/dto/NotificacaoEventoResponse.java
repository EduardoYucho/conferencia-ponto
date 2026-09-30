package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.evento.NotificacaoCriadaEvento;

/** Payload do evento SSE {@code notificacao}. */
public record NotificacaoEventoResponse(NotificacaoResponse notificacao, int naoLidas) {

    public static NotificacaoEventoResponse de(NotificacaoCriadaEvento evento) {
        return new NotificacaoEventoResponse(NotificacaoResponse.de(evento.notificacao()), evento.naoLidas());
    }
}
