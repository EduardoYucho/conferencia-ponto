package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

/**
 * PUT /api/v1/jornadas/{data}/batidas — lista FINAL de batidas do dia.
 * Ex.: {"horarios": ["08:05:53", "12:00", "12:58:33", "18:03:22"], "justificativa": "Corrigido pelo RH"}
 */
public record AjusteBatidasRequest(
        @NotEmpty(message = "Informe ao menos uma batida.")
        @Size(max = 4, message = "O dia comporta no máximo 4 batidas.")
        List<@NotNull(message = "Há batida sem horário.") LocalTime> horarios,
        @NotBlank(message = "Informe o motivo do ajuste.")
        @Size(min = 5, max = 500, message = "A justificativa deve ter de 5 a 500 caracteres.")
        String justificativa) {
}
