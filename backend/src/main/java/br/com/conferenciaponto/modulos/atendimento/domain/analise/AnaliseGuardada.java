package br.com.conferenciaponto.modulos.atendimento.domain.analise;

import java.time.Instant;
import java.util.UUID;

/**
 * A análise de um arquivo (atendimento.analise), já mascarada.
 *
 * @param resultado o JSON da resposta, no esquema da versão do prompt
 * @param tokensEntrada null quando a análise foi reaproveitada de outro arquivo com o mesmo conteúdo
 */
public record AnaliseGuardada(UUID id, UUID arquivoId, int versaoPrompt, String modelo, String resultado,
                              Integer tokensEntrada, Integer tokensSaida, Integer duracaoMs, Instant criadaEm) {
}
