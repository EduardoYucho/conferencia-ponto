package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;

import java.time.LocalDate;

/** Payload do evento SSE {@code jornada-atualizada}. {@code registro} é null na exclusão. */
public record EventoJornadaResponse(
        OrigemAtualizacao origem,
        LocalDate data,
        RegistroJornadaResponse registro,
        String mensagem) {

    public static EventoJornadaResponse de(JornadaAtualizadaEvento e) {
        return new EventoJornadaResponse(
                e.origem(),
                e.data(),
                e.registro() == null ? null : RegistroJornadaResponse.de(e.registro()),
                e.mensagem());
    }
}
