package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;

/**
 * A última leitura de um alvo, com desde quando ele está nessa situação.
 *
 * @param desde         quando entrou na situação de agora
 * @param ruinsSeguidas quantas leituras ruins seguidas (os avisos da etapa M3 esperam duas)
 */
public record EstadoAtual(Leitura leitura, Instant lidaEm, Instant desde, int ruinsSeguidas) {

    public Alvo alvo() {
        return leitura.alvo();
    }

    public Situacao situacao() {
        return leitura.situacao();
    }
}
