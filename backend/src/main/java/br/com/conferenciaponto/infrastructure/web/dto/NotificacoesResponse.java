package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.NotificacoesUseCase;

import java.util.List;

public record NotificacoesResponse(int naoLidas, List<NotificacaoResponse> notificacoes) {

    public static NotificacoesResponse de(NotificacoesUseCase.Caixa caixa) {
        return new NotificacoesResponse(caixa.naoLidas(),
                caixa.notificacoes().stream().map(NotificacaoResponse::de).toList());
    }
}
