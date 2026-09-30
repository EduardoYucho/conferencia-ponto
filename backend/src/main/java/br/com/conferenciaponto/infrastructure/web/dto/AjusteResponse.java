package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.AjusteJornada;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Um ajuste manual do histórico: batidas antes e depois, motivo, quem e quando. */
public record AjusteResponse(
        UUID id,
        LocalDate data,
        @JsonFormat(pattern = "HH:mm:ss") List<LocalTime> antes,
        @JsonFormat(pattern = "HH:mm:ss") List<LocalTime> depois,
        String justificativa,
        String usuario,
        Instant ajustadoEm) {

    public static AjusteResponse de(AjusteJornada a) {
        return new AjusteResponse(a.id(), a.data(), a.antes(), a.depois(), a.justificativa(), a.usuario(),
                a.ajustadoEm());
    }
}
