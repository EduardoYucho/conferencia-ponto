package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** @param dataFimPrevista opcional: sem ela, início + duração do ciclo */
public record CorrigirCicloRequest(
        @NotNull(message = "Informe o início do ciclo.") LocalDate dataInicio,
        LocalDate dataFimPrevista) {
}
