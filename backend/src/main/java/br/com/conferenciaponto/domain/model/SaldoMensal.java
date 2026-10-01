package br.com.conferenciaponto.domain.model;

/**
 * Consolidação mensal do banco de horas, em segundos: jornadas fechadas e, no saldo, os lançamentos
 * avulsos no banco ({@code segundosLancados}, já somados em {@code saldoMensalSegundos}).
 */
public record SaldoMensal(
        int ano,
        int mes,
        int diasRegistrados,
        int diasEmAberto,
        int segundosTrabalhados,
        int segundosPrevistos,
        int saldoMensalSegundos,
        int saldoAnualAcumuladoSegundos,
        int segundosLancados) {

    /** Só jornadas (sem lançamentos no banco). */
    public SaldoMensal(int ano, int mes, int diasRegistrados, int diasEmAberto, int segundosTrabalhados,
                       int segundosPrevistos, int saldoMensalSegundos, int saldoAnualAcumuladoSegundos) {
        this(ano, mes, diasRegistrados, diasEmAberto, segundosTrabalhados, segundosPrevistos, saldoMensalSegundos,
                saldoAnualAcumuladoSegundos, 0);
    }

    /** Saldo só das jornadas (sem os lançamentos avulsos). */
    public int saldoJornadasSegundos() {
        return saldoMensalSegundos - segundosLancados;
    }

    public static SaldoMensal vazio(int ano, int mes, int saldoAnualAcumuladoSegundos) {
        return new SaldoMensal(ano, mes, 0, 0, 0, 0, 0, saldoAnualAcumuladoSegundos);
    }
}
