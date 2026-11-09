package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;

/** Um alvo trocou de situação ({@code de} vazio = a primeira leitura dele). */
public record Mudanca(Alvo alvo, Situacao de, Situacao para, String detalhe, Instant em) {
}
