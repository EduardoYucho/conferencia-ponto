package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import java.util.UUID;

/**
 * Cifra e decifra as chaves do Gemini. A cifra fica presa ao dono: uma chave cifrada para um usuário não é
 * decifrada para outro.
 */
public interface Cofre {

    ChaveCifrada cifrar(String texto, UUID dono);

    /** @throws CofreException se a chave mestra mudou (ou sumiu) desde que a chave foi guardada */
    String decifrar(ChaveCifrada cifrada, UUID dono);
}
