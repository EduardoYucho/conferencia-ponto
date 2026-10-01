package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.GerenciarHorariosUseCase;

import java.util.List;

/** Vigências depois da alteração e quantos dias já registrados foram recalculados. */
public record AlteracaoHorarioResponse(List<HorarioResponse> vigencias, int diasRecalculados) {

    public static AlteracaoHorarioResponse de(GerenciarHorariosUseCase.Alteracao a) {
        return new AlteracaoHorarioResponse(a.vigencias().stream().map(HorarioResponse::de).toList(),
                a.diasRecalculados());
    }
}
