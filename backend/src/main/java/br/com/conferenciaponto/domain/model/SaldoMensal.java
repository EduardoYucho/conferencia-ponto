package br.com.conferenciaponto.domain.model;

/**
 * Consolidação mensal do banco de horas (apenas jornadas fechadas), em segundos.
 */
public record SaldoMensal(
        int ano,
        int mes,
        int diasRegistrados,
        int diasEmAberto,
        int segundosTrabalhados,
        int segundosPrevistos,
        int saldoMensalSegundos,
        int saldoAnualAcumuladoSegundos) {

    public static SaldoMensal vazio(int ano, int mes, int saldoAnualAcumuladoSegundos) {
        return new SaldoMensal(ano, mes, 0, 0, 0, 0, 0, saldoAnualAcumuladoSegundos);
    }
}
