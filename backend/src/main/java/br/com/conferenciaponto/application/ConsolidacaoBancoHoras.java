package br.com.conferenciaponto.application;

import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Saldo do banco de horas por mês: o das jornadas fechadas (consolidado pelo banco de dados) mais os
 * lançamentos avulsos (débitos/créditos com justificativa). É o saldo do painel e do ciclo; a conciliação
 * com o RH compara só as jornadas.
 */
@Component
public class ConsolidacaoBancoHoras {

    private final RegistroJornadaRepository registros;
    private final LancamentoBancoRepository lancamentos;

    public ConsolidacaoBancoHoras(RegistroJornadaRepository registros, LancamentoBancoRepository lancamentos) {
        this.registros = registros;
        this.lancamentos = lancamentos;
    }

    /** Meses do ano com jornada ou lançamento; acumulado desde janeiro. */
    public List<SaldoMensal> ano(int ano) {
        return mesclar(registros.consolidarAno(ano),
                lancamentos.listarNoPeriodo(LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31)));
    }

    /** Meses do período com jornada ou lançamento; acumulado desde o início do período. */
    public List<SaldoMensal> periodo(LocalDate inicio, LocalDate fim) {
        return mesclar(registros.consolidarPeriodo(inicio, fim), lancamentos.listarNoPeriodo(inicio, fim));
    }

    static List<SaldoMensal> mesclar(List<SaldoMensal> jornadas, List<LancamentoBanco> avulsos) {
        Map<YearMonth, SaldoMensal> porMes = jornadas.stream()
                .collect(Collectors.toMap(s -> YearMonth.of(s.ano(), s.mes()), Function.identity(), (a, b) -> a, TreeMap::new));
        Map<YearMonth, Integer> lancadoPorMes = avulsos.stream()
                .collect(Collectors.groupingBy(l -> YearMonth.from(l.data()), TreeMap::new,
                        Collectors.summingInt(LancamentoBanco::segundos)));

        TreeSet<YearMonth> meses = new TreeSet<>(porMes.keySet());
        meses.addAll(lancadoPorMes.keySet());
        List<SaldoMensal> resultado = new ArrayList<>(meses.size());
        int acumulado = 0;
        for (YearMonth mes : meses) {
            SaldoMensal j = porMes.getOrDefault(mes, SaldoMensal.vazio(mes.getYear(), mes.getMonthValue(), 0));
            int lancado = lancadoPorMes.getOrDefault(mes, 0);
            int saldo = j.saldoJornadasSegundos() + lancado;
            acumulado += saldo;
            resultado.add(new SaldoMensal(j.ano(), j.mes(), j.diasRegistrados(), j.diasEmAberto(),
                    j.segundosTrabalhados(), j.segundosPrevistos(), saldo, acumulado, lancado));
        }
        return resultado;
    }
}
