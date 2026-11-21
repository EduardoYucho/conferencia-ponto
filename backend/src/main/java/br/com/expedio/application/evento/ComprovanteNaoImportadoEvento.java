package br.com.expedio.application.evento;

import java.util.UUID;

import br.com.expedio.domain.model.StatusImportacao;

import java.time.LocalDateTime;

/** Um PDF foi lido, mas não gerou batida (duplicado, rejeitado ou inválido). */
public record ComprovanteNaoImportadoEvento(
        UUID usuarioId,
        String nomeArquivo,
        StatusImportacao status,
        LocalDateTime dataHoraBatida,
        String mensagem) {
}
