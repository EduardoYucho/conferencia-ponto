package br.com.conferenciaponto.domain.exception;

/** O usuário logado não pode ver ou alterar os dados pedidos (HTTP 403). */
public class AcessoNegadoException extends DominioException {

    public AcessoNegadoException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
