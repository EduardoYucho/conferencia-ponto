package br.com.conferenciaponto.infrastructure.web.acesso;

import br.com.conferenciaponto.domain.model.Usuario;

import java.util.UUID;

/**
 * Dono dos dados de ponto de uma requisição (o "titular") e quem está logado. Nos GET, o administrador e a
 * coordenação escolhem o titular com {@code ?usuario=login}; nas alterações o titular é sempre o próprio
 * usuário logado.
 *
 * @param logado quem fez a requisição (vai para a auditoria: "ajustado por", "lançado por"...)
 */
public record Titular(UUID id, String login, String nome, Usuario logado) {

    public static Titular de(Usuario titular, Usuario logado) {
        return new Titular(titular.id(), titular.login(), titular.nome(), logado);
    }

    /** Login de quem fez a alteração. */
    public String quem() {
        return logado.login();
    }

    public boolean proprio() {
        return id.equals(logado.id());
    }
}
