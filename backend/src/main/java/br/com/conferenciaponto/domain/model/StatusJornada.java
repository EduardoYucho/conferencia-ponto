package br.com.conferenciaponto.domain.model;

public enum StatusJornada {
    /** Todas as entradas possuem saída correspondente: saldo apurado. */
    FECHADA,
    /** Existe entrada sem saída (ex.: jornada do dia corrente). Saldo ainda não apurado. */
    EM_ANDAMENTO
}
