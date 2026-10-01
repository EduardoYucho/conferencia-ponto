package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Perfil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Cadastro feito pelo administrador: a pessoa troca a senha provisória no primeiro acesso. */
public record NovoUsuarioRequest(
        @NotBlank(message = "Informe o login.") String login,
        @NotBlank(message = "Informe o nome.") String nome,
        @NotNull(message = "Escolha o perfil.") Perfil perfil,
        @NotBlank(message = "Informe a senha provisória.") String senhaProvisoria,
        String pastaComprovantes) {
}
