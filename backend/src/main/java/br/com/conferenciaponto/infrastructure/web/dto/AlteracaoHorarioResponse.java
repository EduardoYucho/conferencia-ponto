package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.GerenciarHorariosUseCase;

import java.util.List;

/** Vigências depois da alteração e quantos dias já registrados foram recalculados. */
public record AlteracaoHorarioResponse(List<HorarioResponse> vigencias, int diasRecalculados) {

    public static AlteracaoHorarioResponse de(GerenciarHorariosUseCase.Alteracao a,
                                              List<GerenciarHorariosUseCase.Vigencia> comAvisos) {
        return new AlteracaoHorarioResponse(comAvisos.stream().map(HorarioResponse::de).toList(), a.diasRecalculados());
    }
}
