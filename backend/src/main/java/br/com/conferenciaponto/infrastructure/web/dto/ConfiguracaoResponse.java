package br.com.conferenciaponto.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;

/** Parâmetros da jornada + data/hora do servidor (referência para o front-end). */
public record ConfiguracaoResponse(
        Grade grade,
        int jornadaBaseSegundos,
        long toleranciaMinutos,
        String fusoHorario,
        LocalDate hoje,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime agora) {

    public record Grade(
            @JsonFormat(pattern = "HH:mm") LocalTime entrada1,
            @JsonFormat(pattern = "HH:mm") LocalTime saida1,
            @JsonFormat(pattern = "HH:mm") LocalTime entrada2,
            @JsonFormat(pattern = "HH:mm") LocalTime saida2) {
    }
}
