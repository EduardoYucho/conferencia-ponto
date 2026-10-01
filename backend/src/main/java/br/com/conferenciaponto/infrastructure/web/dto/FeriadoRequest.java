package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.AbrangenciaFeriado;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record FeriadoRequest(
        @NotNull(message = "Informe a data.") LocalDate data,
        @NotBlank(message = "Informe o nome do feriado (ex.: Corpus Christi).")
        @Size(max = 120, message = "O nome pode ter no máximo 120 caracteres.") String descricao,
        AbrangenciaFeriado abrangencia) {
}
