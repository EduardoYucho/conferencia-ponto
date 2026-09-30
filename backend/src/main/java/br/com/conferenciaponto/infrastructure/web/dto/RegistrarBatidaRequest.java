package br.com.conferenciaponto.infrastructure.web.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ambos opcionais: sem data/horário, o servidor usa o relógio atual ("bater ponto agora").
 * Formatos: data {@code yyyy-MM-dd}, horário {@code HH:mm} ou {@code HH:mm:ss}.
 */
public record RegistrarBatidaRequest(LocalDate data, LocalTime horario) {
}
