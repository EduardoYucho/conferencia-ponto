package br.com.conferenciaponto.domain.model;

/** De onde vem o feriado (o que muda é só o rótulo: todos têm jornada base zero). */
public enum AbrangenciaFeriado {
    NACIONAL("Nacional"),
    ESTADUAL("Estadual"),
    MUNICIPAL("Municipal"),
    /** Dado pela empresa ou ponto facultativo (ex.: Carnaval, Corpus Christi). */
    EMPRESA("Empresa / ponto facultativo");

    private final String rotulo;

    AbrangenciaFeriado(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
