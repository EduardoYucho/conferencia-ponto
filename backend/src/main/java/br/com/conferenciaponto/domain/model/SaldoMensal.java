package br.com.conferenciaponto.domain.model;

/**
 * Consolidação mensal do banco de horas, em segundos: jornadas fechadas e, no saldo, os lançamentos
 * avulsos no banco ({@code segundosLancados}) e a diferença da carga semanal ({@code segundosDaCarga}: negativo
 * quando o horário não fecha a carga), os dois já somados em {@code saldoMensalSegundos}.
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
        int segundosLancados,
        int segundosDaCarga) {

    /** Sem a diferença da carga semanal. */
    public SaldoMensal(int ano, int mes, int diasRegistrados, int diasEmAberto, int segundosTrabalhados,
                       int segundosPrevistos, int saldoMensalSegundos, int saldoAnualAcumuladoSegundos,
                       int segundosLancados) {
        this(ano, mes, diasRegistrados, diasEmAberto, segundosTrabalhados, segundosPrevistos, saldoMensalSegundos,
                saldoAnualAcumuladoSegundos, segundosLancados, 0);
    }

    /** Só jornadas (sem lançamentos no banco). */
    public SaldoMensal(int ano, int mes, int diasRegistrados, int diasEmAberto, int segundosTrabalhados,
                       int segundosPrevistos, int saldoMensalSegundos, int saldoAnualAcumuladoSegundos) {
        this(ano, mes, diasRegistrados, diasEmAberto, segundosTrabalhados, segundosPrevistos, saldoMensalSegundos,
                saldoAnualAcumuladoSegundos, 0, 0);
    }

    /** Saldo só das jornadas (sem os lançamentos avulsos nem a diferença da carga). */
    public int saldoJornadasSegundos() {
        return saldoMensalSegundos - segundosLancados - segundosDaCarga;
    }

    /** O mesmo mês com outro acumulado. */
    public SaldoMensal comAcumulado(int acumulado) {
        return new SaldoMensal(ano, mes, diasRegistrados, diasEmAberto, segundosTrabalhados, segundosPrevistos,
                saldoMensalSegundos, acumulado, segundosLancados, segundosDaCarga);
    }

    public static SaldoMensal vazio(int ano, int mes, int saldoAnualAcumuladoSegundos) {
        return new SaldoMensal(ano, mes, 0, 0, 0, 0, 0, saldoAnualAcumuladoSegundos);
    }
}
