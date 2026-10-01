package br.com.conferenciaponto.domain.model;

/** Motivo de uma ausência que isenta o dia da jornada base. */
public enum TipoAusencia {
    FERIAS("Férias"),
    ATESTADO("Atestado"),
    LICENCA("Licença"),
    /** Folga concedida pela empresa (ex.: folga de aniversário). */
    FOLGA("Folga"),
    /** Outra falta justificada/abonada (ex.: doação de sangue, declaração de comparecimento). */
    ABONO("Abono");

    private final String rotulo;

    TipoAusencia(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
