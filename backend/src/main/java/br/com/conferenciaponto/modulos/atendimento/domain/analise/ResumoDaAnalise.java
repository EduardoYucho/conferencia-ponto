package br.com.conferenciaponto.modulos.atendimento.domain.analise;

import java.time.Instant;
import java.util.UUID;

/** Uma análise guardada sem o conteúdo (para a situação de cada arquivo e os tokens do atendimento). */
public record ResumoDaAnalise(UUID arquivoId, int versaoPrompt, String modelo, Integer tokensEntrada, Integer tokensSaida,
                              Instant criadaEm) {
}
