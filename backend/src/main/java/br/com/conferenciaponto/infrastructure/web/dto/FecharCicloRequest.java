package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * @param ultimoDia último dia incluído no saldo final (opcional: sem ele vale a sugestão do sistema)
 */
public record FecharCicloRequest(
        LocalDate ultimoDia,
        @Size(max = 200, message = "A observação pode ter no máximo 200 caracteres.") String observacao) {
}
