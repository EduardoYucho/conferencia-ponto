package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.Size;

public record ManterLocalRequest(
        @Size(max = 300, message = "A observação pode ter no máximo 300 caracteres.") String observacao) {
}
