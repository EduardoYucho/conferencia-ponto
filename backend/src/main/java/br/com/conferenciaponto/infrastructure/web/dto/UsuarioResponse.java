package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Usuario;

import java.util.List;
import java.util.UUID;

/**
 * Dados do usuário logado (sem hash de senha). O menu e as telas decidem pelas permissões; quem garante é o
 * servidor, que confere cada uma.
 *
 * @param perfis       o nome dos perfis da pessoa
 * @param permissoes   o que vale para ela agora (códigos, ex.: ponto.lancar)
 * @param podeEscrever lança e corrige o próprio ponto
 * @param titular      tem o próprio ponto
 * @param podeVerTodos consulta o ponto de qualquer pessoa
 * @param trocarSenha  a senha é provisória: a tela pede a troca antes de qualquer outra coisa
 */
public record UsuarioResponse(UUID id, String login, String nome, List<String> perfis, List<String> permissoes,
                              boolean podeEscrever, boolean titular, boolean podeVerTodos, boolean trocarSenha,
                              String pastaComprovantes) {

    public static UsuarioResponse de(Usuario u) {
        return new UsuarioResponse(u.id(), u.login(), u.nome(), u.acesso().perfis(), u.acesso().ordenadas(),
                u.podeEscrever(), u.isTitular(), u.podeVerTodos(), u.trocarSenha(), u.pastaComprovantes());
    }
}
