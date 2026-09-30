package br.com.conferenciaponto.application.evento;

import java.util.UUID;

/** Relatório do RH lido e gravado: a conferência com os dias locais roda em segundo plano, após o commit. */
public record RelatorioRhRecebidoEvento(UUID relatorioId) {
}
