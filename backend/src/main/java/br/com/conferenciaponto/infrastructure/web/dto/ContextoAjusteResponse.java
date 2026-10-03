package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.tela.NomesDeBatida;
import br.com.conferenciaponto.application.usecase.AjustarBatidasUseCase;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.TipoBatida;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Dados da tela de ajuste: batidas travadas (têm PDF), histórico de ajustes do dia e os nomes das batidas.
 *
 * @param nomes como a pessoa chama a 1ª, a 2ª... batida do dia, pelo horário dela ("Entrada", "Saída p/ almoço",
 *              "Volta do almoço", "Saída"); uma posição para cada batida que o dia comporta
 */
public record ContextoAjusteResponse(
        @JsonFormat(pattern = "HH:mm:ss") List<LocalTime> comprovadas,
        List<AjusteResponse> historico,
        List<String> nomes) {

    /**
     * @param grade horário da pessoa no dia ({@code null} em dia sem expediente, feriado, folga, férias...)
     */
    public static ContextoAjusteResponse de(AjustarBatidasUseCase.Contexto contexto, GradeHoraria grade) {
        return new ContextoAjusteResponse(contexto.comprovadas(),
                contexto.historico().stream().map(AjusteResponse::de).toList(),
                IntStream.range(0, TipoBatida.MAXIMO).mapToObj(posicao -> NomesDeBatida.de(grade, posicao)).toList());
    }
}
