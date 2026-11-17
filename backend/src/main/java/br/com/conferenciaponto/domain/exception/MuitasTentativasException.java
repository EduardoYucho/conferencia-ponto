package br.com.conferenciaponto.domain.exception;

import java.time.Duration;

/** Tentativas demais em pouco tempo (HTTP 429): a pessoa espera {@link #getEspera()} e tenta de novo. */
public class MuitasTentativasException extends DominioException {

    private final Duration espera;

    public MuitasTentativasException(String codigo, String mensagem, Duration espera) {
        super(codigo, mensagem);
        this.espera = espera;
    }

    public Duration getEspera() {
        return espera;
    }
}
