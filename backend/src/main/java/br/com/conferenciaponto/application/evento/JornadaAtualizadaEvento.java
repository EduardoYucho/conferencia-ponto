package br.com.conferenciaponto.application.evento;

import java.util.UUID;

import br.com.conferenciaponto.application.view.RegistroJornadaView;

import java.time.LocalDate;

/**
 * Publicado pelos casos de uso sempre que a jornada de um dia muda.
 * Entregue aos clientes (SSE) somente após o commit da transação.
 *
 * @param registro estado atual do dia; null quando {@link OrigemAtualizacao#EXCLUSAO}
 */
public record JornadaAtualizadaEvento(
        UUID usuarioId,
        LocalDate data,
        OrigemAtualizacao origem,
        RegistroJornadaView registro,
        String mensagem) {
}
