package br.com.conferenciaponto.application.evento;

import br.com.conferenciaponto.domain.model.StatusImportacao;

import java.time.LocalDateTime;

/** Um PDF foi lido, mas não gerou batida (duplicado, rejeitado ou inválido). */
public record ComprovanteNaoImportadoEvento(
        String nomeArquivo,
        StatusImportacao status,
        LocalDateTime dataHoraBatida,
        String mensagem) {
}
