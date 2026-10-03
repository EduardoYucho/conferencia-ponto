package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.util.List;
import java.util.Map;

/**
 * Painel da conciliação.
 *
 * @param relatorios        relatórios enviados, com o comparativo de saldo no período conferido de cada um
 * @param aceitaveisPorTipo das diferenças para decidir, quantas dá para resolver com "Usar o do RH" (por tipo)
 * @param comparacao        o resultado geral: em quantos dias o RH e o sistema batem
 */
public record ConciliacaoResumoView(List<Comparativo> relatorios, Map<TipoDivergencia, Integer> pendentesPorTipo,
                                    Map<StatusDivergencia, Integer> porStatus,
                                    Map<TipoDivergencia, Integer> aceitaveisPorTipo, Comparacao comparacao) {

    /** Sem o resultado geral (nenhum dia conferido). */
    public ConciliacaoResumoView(List<Comparativo> relatorios, Map<TipoDivergencia, Integer> pendentesPorTipo,
                                 Map<StatusDivergencia, Integer> porStatus) {
        this(relatorios, pendentesPorTipo, porStatus, Map.of(), Comparacao.NADA);
    }

    /** Diferenças que já têm decisão: usado o do RH, mantido como está ou resolvidas por outro caminho. */
    public int decididas() {
        return total() - porStatus.getOrDefault(StatusDivergencia.PENDENTE, 0);
    }

    /** Todas as diferenças, decididas ou não. */
    public int total() {
        return porStatus.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * @param saldoRhSegundos    soma do "Hr. Extra/Falta" dos dias conferidos (até a véspera da emissão)
     * @param saldoLocalSegundos soma dos saldos da conferência nas mesmas datas (dias fechados)
     * @param diasEmAbertoLocal  dias da conferência no período com batida faltando (fora do saldo)
     * @param diasDiferentes     dias do relatório que continuam diferentes do sistema (para decidir ou mantidos)
     */
    public record Comparativo(RelatorioRh relatorio, int diasConferidos, int saldoRhSegundos, int saldoLocalSegundos,
                              int diasEmAbertoLocal, int pendentes, int diasDiferentes) {
    }

    /**
     * O resultado da comparação, somando todos os relatórios (cada data conta uma vez, pelo relatório mais novo).
     *
     * @param diasConferidos dias comparados
     * @param diasIguais     dias em que o RH e o sistema batem
     * @param paraDecidir    dias diferentes esperando decisão
     * @param mantidos       dias diferentes que a pessoa decidiu manter como estão no sistema
     */
    public record Comparacao(int diasConferidos, int diasIguais, int paraDecidir, int mantidos) {

        public static final Comparacao NADA = new Comparacao(0, 0, 0, 0);
    }
}
