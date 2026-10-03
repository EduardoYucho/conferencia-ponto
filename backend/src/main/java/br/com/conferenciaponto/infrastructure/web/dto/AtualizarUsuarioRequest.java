package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Perfil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AtualizarUsuarioRequest(
        @NotBlank(message = "Informe o nome.") String nome,
        @NotNull(message = "Escolha o perfil.") Perfil perfil,
        @NotNull(message = "Informe se o usuário está ativo.") Boolean ativo) {
}
