package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;

import java.time.Instant;
import java.util.UUID;

public record NotificacaoResponse(UUID id, TipoNotificacao tipo, String titulo, String mensagem, String link,
                                  Instant criadaEm, Instant lidaEm, boolean lida) {

    public static NotificacaoResponse de(Notificacao n) {
        return new NotificacaoResponse(n.id(), n.tipo(), n.titulo(), n.mensagem(), n.link(), n.criadaEm(), n.lidaEm(),
                n.isLida());
    }
}
