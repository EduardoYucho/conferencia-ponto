package br.com.conferenciaponto.domain.exception;

/** Login ou senha incorretos, ou usuário inativo (HTTP 401). */
public class CredenciaisInvalidasException extends DominioException {

    public CredenciaisInvalidasException() {
        super("CREDENCIAIS_INVALIDAS", "Login ou senha inválidos.");
    }
}
