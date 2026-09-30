package br.com.conferenciaponto.domain.model;

public enum TipoNotificacao {
    /** Faltam 30 dias (ou menos) para a previsão de fechamento do banco de horas. */
    CICLO_30_DIAS,
    /** Faltam 15 dias (ou menos). */
    CICLO_15_DIAS,
    /** A previsão passou e o ciclo continua aberto. */
    CICLO_VENCIDO,
    /** Relatório do RH conferido (conciliação concluída). */
    CONCILIACAO
}
