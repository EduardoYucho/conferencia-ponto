package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;

public record FechamentoCicloResponse(CicloBancoResponse fechado, CicloBancoResponse novo) {

    public static FechamentoCicloResponse de(GerenciarCicloBancoUseCase.Fechamento f) {
        return new FechamentoCicloResponse(CicloBancoResponse.de(f.fechado()), CicloBancoResponse.de(f.novo()));
    }
}
