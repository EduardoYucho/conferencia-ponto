package br.com.expedio.domain.exception;

/** Violação de regra de negócio (HTTP 422). */
public class RegraNegocioException extends DominioException {

    public RegraNegocioException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
