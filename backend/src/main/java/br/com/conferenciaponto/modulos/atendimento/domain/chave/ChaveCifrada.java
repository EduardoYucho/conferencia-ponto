package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import java.util.Objects;

/**
 * A chave do Gemini como fica guardada: cifrada, com o vetor inicial usado e a versão da chave mestra que cifrou.
 * Nunca tem o texto da chave.
 */
public record ChaveCifrada(byte[] cifrada, byte[] vetorInicial, int versaoChaveMestra) {

    public ChaveCifrada {
        Objects.requireNonNull(cifrada, "cifrada");
        Objects.requireNonNull(vetorInicial, "vetorInicial");
        cifrada = cifrada.clone();
        vetorInicial = vetorInicial.clone();
    }

    @Override
    public byte[] cifrada() {
        return cifrada.clone();
    }

    @Override
    public byte[] vetorInicial() {
        return vetorInicial.clone();
    }

    @Override
    public String toString() {
        return "ChaveCifrada[" + cifrada.length + " bytes, chave mestra v" + versaoChaveMestra + "]";
    }
}
