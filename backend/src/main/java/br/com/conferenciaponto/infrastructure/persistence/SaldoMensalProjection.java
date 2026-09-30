package br.com.conferenciaponto.infrastructure.persistence;

/** Projeção da view vw_saldo_mensal (aliases entre aspas na query nativa). */
public interface SaldoMensalProjection {

    Integer getAno();

    Integer getMes();

    Integer getDiasRegistrados();

    Integer getDiasEmAberto();

    Integer getSegundosTrabalhados();

    Integer getSegundosPrevistos();

    Integer getSaldoMensalSegundos();

    Integer getSaldoAnualAcumuladoSegundos();
}
