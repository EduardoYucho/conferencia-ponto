package br.com.conferenciaponto.application;

import br.com.conferenciaponto.application.view.CicloBancoView;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O banco de horas no fim de cada mês: o saldo acumulado, desde o início do ciclo, do ciclo que cobre o mês. Quando
 * um ciclo fecha no meio do mês, vale o do ciclo seguinte. Segue o período de cada ciclo (corrigir o início do ciclo
 * muda o banco de todos os meses dele), e no mês corrente é o mesmo saldo do painel. É o número da auditoria e da
 * planilha de conferência; o acumulado do ano (de janeiro em diante) não é o banco de horas, porque atravessa o
 * fechamento do ciclo pelo RH.
 */
public final class BancoDeHorasPorMes {

    /**
     * O banco de horas de um mês.
     *
     * @param saldoSegundos o acumulado do ciclo até o fim do mês (no mês corrente, até agora)
     * @param ciclo         o ciclo que cobre o mês
     */
    public record NoMes(YearMonth mes, int saldoSegundos, CicloBancoView ciclo) {
    }

    private BancoDeHorasPorMes() {
    }

    /** Os meses cobertos pelos ciclos (os meses fora de todos os ciclos não entram). */
    public static Map<YearMonth, NoMes> de(List<CicloBancoView> ciclos) {
        Map<YearMonth, NoMes> porMes = new HashMap<>();
        ciclos.stream()
                .sorted(Comparator.comparing((CicloBancoView v) -> v.ciclo().dataInicio()))
                .forEach(v -> v.meses().forEach(m -> {
                    YearMonth mes = YearMonth.of(m.ano(), m.mes());
                    porMes.put(mes, new NoMes(mes, m.saldoAnualAcumuladoSegundos(), v));
                }));
        return porMes;
    }
}
