package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.ConsultarConciliacaoUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarConciliacaoUseCase.Textos;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @param tipos      todas as categorias, com rótulo e quantidade pendente (para os filtros e o aceite em lote)
 * @param comparacao o resultado geral, em números e numa frase
 * @param contagem   quantas diferenças há em cada filtro da tela: PENDENTE, DECIDIDAS, ACEITO_RH, MANTIDO_LOCAL,
 *                   RESOLVIDA e TODAS
 */
public record ConciliacaoResumoResponse(List<RelatorioRhResponse> relatorios, List<Tipo> tipos,
                                        Map<StatusDivergencia, Integer> porStatus, int pendentes,
                                        Comparacao comparacao, Map<String, Integer> contagem) {

    /**
     * @param nome       o tipo como a tela escreve ("Falta batida no sistema")
     * @param explicacao uma frase dizendo o que é
     * @param aceitaveis das pendentes, em quantas dá para usar o do RH
     * @param sugerido   já vem marcado em "usar o do RH em vários dias"
     */
    public record Tipo(TipoDivergencia tipo, String rotulo, int pendentes, String nome, String explicacao,
                       int aceitaveis, boolean sugerido) {
    }

    /**
     * @param diasIguais  dias em que o RH e o sistema batem
     * @param paraDecidir dias diferentes esperando decisão
     * @param mantidos    dias diferentes mantidos como estão no sistema
     * @param texto       "O RH e o sistema batem em 20 dos 22 dias conferidos. 2 dias têm diferença para decidir."
     *                    ({@code null} sem relatório conferido)
     */
    public record Comparacao(int diasConferidos, int diasIguais, int paraDecidir, int mantidos, String texto) {
    }

    public static ConciliacaoResumoResponse de(ConciliacaoResumoView v) {
        List<Tipo> tipos = Arrays.stream(TipoDivergencia.values())
                .map(t -> new Tipo(t, t.rotulo(), v.pendentesPorTipo().getOrDefault(t, 0), Textos.tipo(t),
                        Textos.explicacao(t), v.aceitaveisPorTipo().getOrDefault(t, 0),
                        ConsultarConciliacaoUseCase.SUGERIDOS_PARA_VARIOS_DIAS.contains(t)))
                .toList();
        int pendentes = v.porStatus().getOrDefault(StatusDivergencia.PENDENTE, 0);
        Map<String, Integer> contagem = new LinkedHashMap<>();
        contagem.put("PENDENTE", pendentes);
        contagem.put("DECIDIDAS", v.decididas());
        for (StatusDivergencia s : List.of(StatusDivergencia.ACEITO_RH, StatusDivergencia.MANTIDO_LOCAL,
                StatusDivergencia.RESOLVIDA)) {
            contagem.put(s.name(), v.porStatus().getOrDefault(s, 0));
        }
        contagem.put("TODAS", v.total());
        ConciliacaoResumoView.Comparacao c = v.comparacao();
        return new ConciliacaoResumoResponse(v.relatorios().stream().map(RelatorioRhResponse::de).toList(), tipos,
                v.porStatus(), pendentes,
                new Comparacao(c.diasConferidos(), c.diasIguais(), c.paraDecidir(), c.mantidos(), Textos.comparacao(c)),
                contagem);
    }
}
