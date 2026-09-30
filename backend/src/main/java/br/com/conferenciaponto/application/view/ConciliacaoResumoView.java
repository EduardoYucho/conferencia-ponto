package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.util.List;
import java.util.Map;

/**
 * Painel da conciliação.
 *
 * @param relatorios relatórios enviados, com o comparativo de saldo no período conferido de cada um
 */
public record ConciliacaoResumoView(List<Comparativo> relatorios, Map<TipoDivergencia, Integer> pendentesPorTipo,
                                    Map<StatusDivergencia, Integer> porStatus) {

    /**
     * @param saldoRhSegundos    soma do "Hr. Extra/Falta" dos dias conferidos (até a véspera da emissão)
     * @param saldoLocalSegundos soma dos saldos da conferência nas mesmas datas (dias fechados)
     * @param diasEmAbertoLocal  dias da conferência no período com batida faltando (fora do saldo)
     */
    public record Comparativo(RelatorioRh relatorio, int diasConferidos, int saldoRhSegundos, int saldoLocalSegundos,
                              int diasEmAbertoLocal, int pendentes) {
    }
}
