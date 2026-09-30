package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AlterarSenhaRequest(
        @NotBlank(message = "Informe a senha atual.") String senhaAtual,
        @NotBlank(message = "Informe a nova senha.") String novaSenha) {
}
