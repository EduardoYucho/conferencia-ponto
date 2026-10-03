package br.com.conferenciaponto.domain.exception;

/** Login ou senha incorretos, ou usuário inativo (HTTP 401). */
public class CredenciaisInvalidasException extends DominioException {

    public CredenciaisInvalidasException() {
        super("CREDENCIAIS_INVALIDAS", "Login ou senha inválidos.");
    }

    private CredenciaisInvalidasException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }

    /** O token ainda vale, mas o usuário foi desativado (ou removido) depois do login. */
    public static CredenciaisInvalidasException acessoDesativado() {
        return new CredenciaisInvalidasException("ACESSO_DESATIVADO",
                "Seu acesso ao sistema foi desativado. Procure o administrador.");
    }
}
