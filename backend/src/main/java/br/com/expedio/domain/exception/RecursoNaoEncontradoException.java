package br.com.expedio.domain.exception;

/** Recurso inexistente (HTTP 404). */
public class RecursoNaoEncontradoException extends DominioException {

    public RecursoNaoEncontradoException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
