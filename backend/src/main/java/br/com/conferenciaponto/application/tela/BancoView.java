package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.LancamentoBanco;

import java.util.List;

/**
 * A tela "Banco de horas": o saldo que o RH zera a cada fechamento, mês a mês, com as horas usadas ou somadas
 * à mão e os fechamentos anteriores.
 *
 * @param resumo       saldo, desde quando e o prazo, já escritos ({@code null} sem ciclo aberto)
 * @param ciclo        o ciclo aberto (para as janelas de fechar e de corrigir o período)
 * @param meses        os meses do ciclo, do mais antigo para o mais recente
 * @param lancamentos  horas usadas ou somadas à mão dentro do ciclo, da mais recente para a mais antiga
 * @param anteriores   fechamentos anteriores, do mais recente para o mais antigo
 * @param podeEditar   quem está olhando pode fechar o banco, corrigir o período e lançar horas
 * @param podeDesfazer existe um fechamento imediatamente anterior ao ciclo atual (dá para desfazer)
 */
public record BancoView(InicioView.Banco resumo, CicloBancoView ciclo, List<Mes> meses, List<Lancamento> lancamentos,
                        List<Anterior> anteriores, boolean podeEditar, boolean podeDesfazer, String explicacao) {

    /**
     * @param rotulo    "Setembro" (com o ano quando o ciclo atravessa a virada: "Janeiro de 2027")
     * @param texto     "+ 1h 52min a favor"
     * @param acumulado saldo do ciclo até o fim deste mês
     * @param atual     é o mês de hoje
     */
    public record Mes(int ano, int mes, String rotulo, int saldoSegundos, String texto, int acumuladoSegundos,
                      boolean atual, int diasEmAberto) {
    }

    /** @param texto "Usou 4h do banco" ou "Somou 2h ao banco" */
    public record Lancamento(LancamentoBanco lancamento, String dia, String texto) {
    }

    /**
     * @param periodo "25/11/2025 a 24/05/2026"
     * @param texto   "fechou com + 3h 10min a favor"
     */
    public record Anterior(CicloBanco ciclo, String periodo, String texto) {
    }
}
