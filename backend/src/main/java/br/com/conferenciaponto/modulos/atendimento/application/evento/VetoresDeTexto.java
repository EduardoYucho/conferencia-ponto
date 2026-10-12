package br.com.conferenciaponto.modulos.atendimento.application.evento;

import java.util.UUID;

/**
 * O que o gerador oferece à base de conhecimento além do evento: o vetor (embedding) de um texto, calculado pelo
 * Gemini com a chave de uma pessoa (a base nunca vê a chave). O modelo, o tamanho e o texto exato (com as instruções
 * do modelo) são decididos por quem pede.
 */
public interface VetoresDeTexto {

    /**
     * @param usuarioId quem paga a chamada: o autor do registro ou quem pesquisa
     * @return o vetor com {@code dimensao} valores, já normalizado (tamanho 1)
     * @throws FalhaNoVetor quando não deu: sem chave, chave recusada, limite, cota, Gemini fora do ar
     */
    float[] calcular(UUID usuarioId, String modelo, int dimensao, String texto);
}
