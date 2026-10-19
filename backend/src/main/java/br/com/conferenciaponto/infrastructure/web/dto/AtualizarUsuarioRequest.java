package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Nome e situação (os perfis e os ajustes mudam em /api/v1/acessos/usuarios/{id}). */
public record AtualizarUsuarioRequest(
        @NotBlank(message = "Informe o nome.") String nome,
        @NotNull(message = "Informe se o usuário está ativo.") Boolean ativo) {
}
