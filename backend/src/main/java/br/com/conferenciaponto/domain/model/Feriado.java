package br.com.conferenciaponto.domain.model;

import java.time.LocalDate;

public record Feriado(LocalDate data, String descricao) {
}
