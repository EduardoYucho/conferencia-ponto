package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.AjustarBatidasUseCase;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;
import java.util.List;

/** Dados da tela de ajuste: batidas travadas (têm PDF) e histórico de ajustes do dia. */
public record ContextoAjusteResponse(
        @JsonFormat(pattern = "HH:mm:ss") List<LocalTime> comprovadas,
        List<AjusteResponse> historico) {

    public static ContextoAjusteResponse de(AjustarBatidasUseCase.Contexto contexto) {
        return new ContextoAjusteResponse(contexto.comprovadas(),
                contexto.historico().stream().map(AjusteResponse::de).toList());
    }
}
