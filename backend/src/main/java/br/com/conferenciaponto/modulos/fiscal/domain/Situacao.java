package br.com.conferenciaponto.modulos.fiscal.domain;

/**
 * A situação de um serviço ou de um autorizador. A ordem é a da gravidade: {@link #pior} escolhe a mais grave, e
 * "sem informação" só vale quando não há nada melhor (não é uma queda).
 */
public enum Situacao {
    /** Respondeu normalmente. */
    NORMAL,
    /** Respondeu devagar, ou o painel avisa alguma anormalidade que não é queda. */
    INSTAVEL,
    /** Não respondeu, ou o painel diz que está fora. */
    FORA,
    /** Não deu para saber (fonte com problema, verificação antiga, sem internet neste computador). */
    SEM_INFORMACAO;

    /** Instável ou fora: entra em incidente e conta contra a disponibilidade. */
    public boolean ruim() {
        return this == INSTAVEL || this == FORA;
    }

    /** A mais grave das duas (fora > instável > normal > sem informação). */
    public static Situacao pior(Situacao a, Situacao b) {
        return gravidade(a) >= gravidade(b) ? a : b;
    }

    private static int gravidade(Situacao s) {
        return switch (s) {
            case SEM_INFORMACAO -> 0;
            case NORMAL -> 1;
            case INSTAVEL -> 2;
            case FORA -> 3;
        };
    }
}
