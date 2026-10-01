package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;

import java.util.List;
import java.util.UUID;

/**
 * Dados do usuário logado (sem hash de senha).
 *
 * @param titular      tem os próprios dados de ponto (ADMIN/USER)
 * @param podeVerTodos consulta os dados de qualquer titular (ADMIN e coordenação)
 * @param trocarSenha  a senha é provisória: a tela pede a troca antes de qualquer outra coisa
 */
public record UsuarioResponse(UUID id, String login, String nome, List<String> perfis, boolean podeEscrever,
                              boolean titular, boolean podeVerTodos, boolean admin, boolean trocarSenha,
                              String pastaComprovantes) {

    public static UsuarioResponse de(Usuario u) {
        return new UsuarioResponse(u.id(), u.login(), u.nome(),
                u.perfis().stream().map(Perfil::name).sorted().toList(), u.podeEscrever(), u.isTitular(),
                u.podeVerTodos(), u.isAdmin(), u.trocarSenha(), u.pastaComprovantes());
    }
}
