package br.com.conferenciaponto.domain.model;

/** Categorias de diferença entre a conferência e o relatório do RH (em ordem de importância). */
public enum TipoDivergencia {
    TIPO_DIA("Tipo do dia"),
    SOMENTE_RH("Só no RH"),
    SOMENTE_LOCAL("Só na conferência"),
    BATIDA_FALTANDO("Batida faltando"),
    BATIDA_SOBRANDO("Batida a mais"),
    HORARIO_DIFERENTE("Horário diferente"),
    SALDO("Saldo diferente"),
    DIFERENCA_SEGUNDOS("Diferença de segundos");

    private final String rotulo;

    TipoDivergencia(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
