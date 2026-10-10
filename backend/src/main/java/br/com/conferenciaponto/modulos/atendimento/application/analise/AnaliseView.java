package br.com.conferenciaponto.modulos.atendimento.application.analise;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A análise de um arquivo aberta na tela.
 *
 * @param atual     false quando é de outra versão do prompt ou de outro modelo (a atual ainda não foi feita)
 * @param resultado o JSON da análise (já mascarado), no esquema da versão
 */
public record AnaliseView(UUID arquivoId, String categoria, int versaoPrompt, String modelo, boolean atual,
                          Instant criadaEm, Integer tokensEntrada, Integer tokensSaida, Integer duracaoMs,
                          JsonNode resultado) {
}
