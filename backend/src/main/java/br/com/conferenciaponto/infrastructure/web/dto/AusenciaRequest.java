package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.TipoAusencia;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record AusenciaRequest(
        @NotNull(message = "Informe o início.") LocalDate dataInicio,
        @NotNull(message = "Informe o fim.") LocalDate dataFim,
        @NotNull(message = "Informe o tipo (FERIAS, ATESTADO, LICENCA ou FOLGA).") TipoAusencia tipo,
        @Size(max = 200, message = "A descrição pode ter no máximo 200 caracteres.") String descricao) {
}
