package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Relatório de banco de horas emitido pelo RH, enviado para conciliação.
 * O PDF não é guardado (tem CPF): ficam só os dados de ponto extraídos e o hash, para não duplicar.
 *
 * @param emitidoEm quando o RH gerou o relatório: dias a partir dessa data não são conferidos (parciais)
 */
public record RelatorioRh(UUID id, UUID usuarioId, String nomeArquivo, String hashSha256, String funcionario, LocalDateTime emitidoEm,
                          LocalDate periodoInicio, LocalDate periodoFim, Integer totalPrevistoSegundos,
                          Integer totalTrabalhadoSegundos, Integer totalSaldoSegundos, int diasLidos,
                          StatusRelatorioRh status, String mensagem, int divergencias, Instant enviadoEm,
                          String enviadoPor, Instant processadoEm) {

    public RelatorioRh {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(nomeArquivo, "nomeArquivo");
        Objects.requireNonNull(hashSha256, "hashSha256");
        Objects.requireNonNull(emitidoEm, "emitidoEm");
        Objects.requireNonNull(periodoInicio, "periodoInicio");
        Objects.requireNonNull(periodoFim, "periodoFim");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(enviadoEm, "enviadoEm");
    }

    public RelatorioRh concluido(int divergenciasEncontradas, Instant quando) {
        return new RelatorioRh(id, usuarioId, nomeArquivo, hashSha256, funcionario, emitidoEm, periodoInicio, periodoFim,
                totalPrevistoSegundos, totalTrabalhadoSegundos, totalSaldoSegundos, diasLidos,
                StatusRelatorioRh.CONCLUIDO, null, divergenciasEncontradas, enviadoEm, enviadoPor, quando);
    }

    public RelatorioRh comErro(String erro, Instant quando) {
        return new RelatorioRh(id, usuarioId, nomeArquivo, hashSha256, funcionario, emitidoEm, periodoInicio, periodoFim,
                totalPrevistoSegundos, totalTrabalhadoSegundos, totalSaldoSegundos, diasLidos, StatusRelatorioRh.ERRO,
                erro, divergencias, enviadoEm, enviadoPor, quando);
    }

    /** Último dia conferido: o fim do período ou a véspera da emissão (o dia da emissão ainda estava em andamento). */
    public LocalDate ultimoDiaConferido() {
        LocalDate vespera = emitidoEm.toLocalDate().minusDays(1);
        return periodoFim.isBefore(vespera) ? periodoFim : vespera;
    }
}
