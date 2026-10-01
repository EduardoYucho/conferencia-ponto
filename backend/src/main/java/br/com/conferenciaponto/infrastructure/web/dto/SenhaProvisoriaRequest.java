package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SenhaProvisoriaRequest(@NotBlank(message = "Informe a senha provisória.") String senhaProvisoria) {
}
