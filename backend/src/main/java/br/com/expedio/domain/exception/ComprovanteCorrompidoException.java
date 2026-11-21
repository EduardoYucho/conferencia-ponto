package br.com.expedio.domain.exception;

/** O arquivo armazenado não confere com o hash registrado (alterado ou danificado). */
public class ComprovanteCorrompidoException extends DominioException {

    public ComprovanteCorrompidoException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
