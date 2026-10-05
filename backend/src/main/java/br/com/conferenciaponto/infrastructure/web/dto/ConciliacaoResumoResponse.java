package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @param tipos todas as categorias, com rótulo e quantidade pendente (para os filtros e o aceite em lote)
 */
public record ConciliacaoResumoResponse(List<RelatorioRhResponse> relatorios, List<Tipo> tipos,
                                        Map<StatusDivergencia, Integer> porStatus, int pendentes) {

    public record Tipo(TipoDivergencia tipo, String rotulo, int pendentes) {
    }

    public static ConciliacaoResumoResponse de(ConciliacaoResumoView v) {
        List<Tipo> tipos = Arrays.stream(TipoDivergencia.values())
                .map(t -> new Tipo(t, t.rotulo(), v.pendentesPorTipo().getOrDefault(t, 0)))
                .toList();
        return new ConciliacaoResumoResponse(v.relatorios().stream().map(RelatorioRhResponse::de).toList(), tipos,
                v.porStatus(), v.porStatus().getOrDefault(StatusDivergencia.PENDENTE, 0));
    }
}
