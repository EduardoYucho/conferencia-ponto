package br.com.conferenciaponto.domain.exception;

/**
 * Base das exceções de domínio. O {@code codigo} é estável e pode ser usado
 * pelo front-end para tratamento específico.
 */
public abstract class DominioException extends RuntimeException {

    private final String codigo;

    protected DominioException(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
