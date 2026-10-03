package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.tela.DiaView.Filtro;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * A tela "Meu ponto": o mês dia a dia.
 *
 * @param dias       os dias até hoje, do mais recente para o mais antigo
 * @param proximos   os dias depois de hoje (para planejar folga e férias), em ordem
 * @param contagem   quantos dias (até hoje) entram em cada filtro
 * @param explicacao como o saldo do dia é calculado, com o horário e a tolerância da pessoa
 */
public record MeuPontoView(YearMonth referencia, String titulo, boolean mesAtual, Totais totais,
                           Map<Filtro, Integer> contagem, List<DiaView> dias, List<DiaView> proximos,
                           String explicacao) {

    /**
     * Totais do mês. Só entram os dias fechados, como no saldo do sistema e no relatório do RH.
     *
     * @param saldoDosDiasSegundos soma dos saldos dos dias
     * @param lancadoSegundos      lançamentos avulsos no banco de horas feitos no mês
     * @param saldoSegundos        saldo do mês: dias + lançamentos
     * @param diasParaCorrigir     dias com batida faltando (ficam fora do saldo até corrigir)
     * @param diasSemRegistro      dias de trabalho que já passaram sem nenhuma batida
     */
    public record Totais(int trabalhadoSegundos, int previstoSegundos, int saldoDosDiasSegundos, int lancadoSegundos,
                         int saldoSegundos, int diasFechados, int diasParaCorrigir, int diasSemRegistro) {
    }
}
