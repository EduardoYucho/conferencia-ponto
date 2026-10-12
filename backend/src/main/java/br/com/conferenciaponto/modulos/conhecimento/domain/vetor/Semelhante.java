package br.com.conferenciaponto.modulos.conhecimento.domain.vetor;

import java.util.UUID;

/**
 * Um registro parecido com a consulta pelo significado.
 *
 * @param similaridade o cosseno entre os vetores (1 = igual; perto de 0 = nada a ver)
 */
public record Semelhante(UUID registroId, double similaridade) {
}
