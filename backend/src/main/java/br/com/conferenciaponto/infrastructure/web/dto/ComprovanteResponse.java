package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.evento.ComprovanteNaoImportadoEvento;
import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.StatusImportacao;

import java.time.Instant;
import java.time.LocalDateTime;

/** Comprovante processado (lista de auditoria e evento SSE {@code comprovante-nao-importado}). */
public record ComprovanteResponse(
        String nomeArquivo,
        StatusImportacao status,
        LocalDateTime dataHoraBatida,
        String mensagem,
        Instant processadoEm) {

    public static ComprovanteResponse de(ComprovanteImportado c) {
        return new ComprovanteResponse(c.nomeArquivo(), c.status(), c.dataHoraBatida(), c.mensagem(), c.processadoEm());
    }

    public static ComprovanteResponse de(ComprovanteNaoImportadoEvento e) {
        return new ComprovanteResponse(e.nomeArquivo(), e.status(), e.dataHoraBatida(), e.mensagem(), Instant.now());
    }
}
