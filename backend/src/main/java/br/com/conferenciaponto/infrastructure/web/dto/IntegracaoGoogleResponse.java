package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase;

/**
 * Conta de serviço do Google configurada no sistema (a chave em si nunca sai do servidor).
 *
 * @param planilhas quantas pessoas já vincularam a planilha delas
 */
public record IntegracaoGoogleResponse(boolean configurada, String email, String projeto, int planilhas) {

    public static IntegracaoGoogleResponse de(GerenciarPlanilhaUseCase.Integracao i) {
        return new IntegracaoGoogleResponse(i.conta() != null, i.conta() == null ? null : i.conta().email(),
                i.conta() == null ? null : i.conta().projeto(), i.planilhas());
    }
}
