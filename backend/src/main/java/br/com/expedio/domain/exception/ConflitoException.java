package br.com.expedio.domain.exception;

/** Operação conflita com o estado atual do recurso (HTTP 409). */
public class ConflitoException extends DominioException {

    public ConflitoException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
