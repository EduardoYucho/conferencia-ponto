package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Trilha de auditoria de cada PDF processado. O hash do conteúdo impede que o
 * mesmo arquivo seja processado duas vezes (ex.: ao reiniciar o serviço).
 *
 * @param dataHoraBatida data/hora lida do PDF; null quando {@link StatusImportacao#INVALIDO}
 */
public record ComprovanteImportado(
        UUID id,
        UUID usuarioId,
        String nomeArquivo,
        String hashSha256,
        LocalDateTime dataHoraBatida,
        StatusImportacao status,
        String mensagem,
        Instant processadoEm) {

    public static ComprovanteImportado novo(UUID usuarioId, String nomeArquivo, String hashSha256, LocalDateTime dataHoraBatida,
                                            StatusImportacao status, String mensagem) {
        return new ComprovanteImportado(UUID.randomUUID(), usuarioId, nomeArquivo, hashSha256, dataHoraBatida,
                status, mensagem, Instant.now());
    }
}
