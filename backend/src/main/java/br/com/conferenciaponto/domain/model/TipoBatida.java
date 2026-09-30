package br.com.conferenciaponto.domain.model;

/**
 * Posições de batida da jornada, na ordem cronológica em que devem ocorrer.
 * O dia comporta até 3 intervalos (6 batidas), como no sistema do RH: ex.: sair às 11:00 para
 * um compromisso e voltar às 11:15, além do almoço.
 */
public enum TipoBatida {
    ENTRADA_1("Entrada 1"),
    SAIDA_1("Saída 1"),
    ENTRADA_2("Entrada 2"),
    SAIDA_2("Saída 2"),
    ENTRADA_3("Entrada 3"),
    SAIDA_3("Saída 3");

    /** Máximo de batidas por dia. */
    public static final int MAXIMO = values().length;

    private final String rotulo;

    TipoBatida(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    public boolean isEntrada() {
        return ordinal() % 2 == 0;
    }

    public static TipoBatida daPosicao(int indice) {
        return values()[indice];
    }
}
