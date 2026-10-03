package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.TipoDivergencia;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.Set;

/** @param inicio/fim opcionais: limitam o aceite a um período */
public record AceiteLoteRequest(
        @NotEmpty(message = "Escolha ao menos um tipo de diferença.") Set<TipoDivergencia> tipos,
        LocalDate inicio,
        LocalDate fim) {
}
