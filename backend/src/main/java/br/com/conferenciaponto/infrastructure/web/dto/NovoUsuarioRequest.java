package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/**
 * Cadastro de uma pessoa: ela troca a senha provisória no primeiro acesso.
 *
 * @param perfis os perfis dela (só quem cuida dos acessos escolhe; vazio = sem acesso até alguém dar um perfil)
 */
public record NovoUsuarioRequest(
        @NotBlank(message = "Informe o login.") String login,
        @NotBlank(message = "Informe o nome.") String nome,
        List<UUID> perfis,
        @NotBlank(message = "Informe a senha provisória.") String senhaProvisoria,
        String pastaComprovantes) {
}
