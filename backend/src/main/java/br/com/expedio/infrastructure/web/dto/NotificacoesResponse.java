package br.com.expedio.infrastructure.web.dto;

import br.com.expedio.application.usecase.NotificacoesUseCase;

import java.util.List;

public record NotificacoesResponse(int naoLidas, List<NotificacaoResponse> notificacoes) {

    public static NotificacoesResponse de(NotificacoesUseCase.Caixa caixa) {
        return new NotificacoesResponse(caixa.naoLidas(),
                caixa.notificacoes().stream().map(NotificacaoResponse::de).toList());
    }
}
