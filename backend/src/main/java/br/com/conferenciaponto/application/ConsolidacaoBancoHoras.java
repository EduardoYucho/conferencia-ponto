package br.com.conferenciaponto.application;

import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Saldo do banco de horas por mês: o das jornadas fechadas (consolidado pelo banco de dados) mais os
 * lançamentos avulsos (débitos/créditos com justificativa) e a diferença da carga semanal ({@link DiferencaDaCarga}).
 * É o saldo do painel e do ciclo; a conciliação com o RH compara só as jornadas.
 */
@Component
public class ConsolidacaoBancoHoras {

    private final RegistroJornadaRepository registros;
    private final LancamentoBancoRepository lancamentos;
    private final DiferencaDaCarga carga;

    /** Sem a diferença da carga semanal (testes). */
    public ConsolidacaoBancoHoras(RegistroJornadaRepository registros, LancamentoBancoRepository lancamentos) {
        this(registros, lancamentos, null);
    }

    @Autowired
    public ConsolidacaoBancoHoras(RegistroJornadaRepository registros, LancamentoBancoRepository lancamentos,
                                  DiferencaDaCarga carga) {
        this.registros = registros;
        this.lancamentos = lancamentos;
        this.carga = carga;
    }

    /** Meses do ano com jornada ou lançamento; acumulado desde janeiro. */
    public List<SaldoMensal> ano(UUID usuarioId, int ano) {
        LocalDate inicio = LocalDate.of(ano, 1, 1);
        LocalDate fim = LocalDate.of(ano, 12, 31);
        return mesclar(registros.consolidarAno(usuarioId, ano), lancamentos.listarNoPeriodo(usuarioId, inicio, fim),
                diferencas(usuarioId, inicio, fim));
    }

    /** Meses do período com jornada ou lançamento; acumulado desde o início do período. */
    public List<SaldoMensal> periodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return mesclar(registros.consolidarPeriodo(usuarioId, inicio, fim),
                lancamentos.listarNoPeriodo(usuarioId, inicio, fim), diferencas(usuarioId, inicio, fim));
    }

    private Map<YearMonth, Integer> diferencas(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return carga == null ? Map.of() : carga.porMes(usuarioId, inicio, fim);
    }

    static List<SaldoMensal> mesclar(List<SaldoMensal> jornadas, List<LancamentoBanco> avulsos) {
        return mesclar(jornadas, avulsos, Map.of());
    }

    static List<SaldoMensal> mesclar(List<SaldoMensal> jornadas, List<LancamentoBanco> avulsos,
                                     Map<YearMonth, Integer> daCarga) {
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
            int diferenca = daCarga.getOrDefault(mes, 0);
            int saldo = j.saldoJornadasSegundos() + lancado + diferenca;
            acumulado += saldo;
            resultado.add(new SaldoMensal(j.ano(), j.mes(), j.diasRegistrados(), j.diasEmAberto(),
                    j.segundosTrabalhados(), j.segundosPrevistos(), saldo, acumulado, lancado, diferenca));
        }
        return resultado;
    }
}
