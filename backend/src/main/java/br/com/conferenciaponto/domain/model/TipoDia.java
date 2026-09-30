package br.com.conferenciaponto.domain.model;

/**
 * Classificação do dia para fins de apuração.
 * Apenas {@link #UTIL} possui carga horária base; os demais têm base zero,
 * de modo que 100% do tempo trabalhado configura saldo credor.
 */
public enum TipoDia {
    UTIL,
    FIM_DE_SEMANA,
    FERIADO,
    /** Dia útil coberto por férias, atestado, licença ou folga: não gera débito. */
    AUSENCIA;

    public boolean isUtil() {
        return this == UTIL;
    }
}
