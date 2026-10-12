package br.com.conferenciaponto.modulos.atendimento.application.evento;

import java.time.Duration;
import java.util.Objects;

/** O vetor não pôde ser calculado, já classificado pelo que fazer. A mensagem é para a pessoa ler. */
public class FalhaNoVetor extends RuntimeException {

    public enum Motivo {
        /** A pessoa não tem chave do Gemini cadastrada (ou ela não abre mais). */
        SEM_CHAVE,
        /** O Google recusou a chave. */
        CHAVE_RECUSADA,
        /** Limite por minuto: tentar de novo depois de {@link #esperar()}. */
        LIMITE,
        /** A cota do dia (ou os créditos) acabou. */
        COTA_DIARIA,
        /** O Gemini não respondeu, ou não há conexão com o Google. */
        INDISPONIVEL,
        /** O Google não aceitou o pedido (modelo inexistente, texto recusado...). */
        RECUSADO
    }

    private final Motivo motivo;
    private final Duration esperar;

    public FalhaNoVetor(Motivo motivo, String mensagem, Duration esperar) {
        super(mensagem);
        this.motivo = Objects.requireNonNull(motivo, "motivo");
        this.esperar = esperar;
    }

    public FalhaNoVetor(Motivo motivo, String mensagem) {
        this(motivo, mensagem, null);
    }

    public Motivo motivo() {
        return motivo;
    }

    /** O tempo que o Google pediu para esperar (no limite por minuto), ou null. */
    public Duration esperar() {
        return esperar;
    }

    /** Passa sozinho com o tempo (vale tentar de novo mais tarde, sem a pessoa fazer nada). */
    public boolean passageira() {
        return motivo == Motivo.LIMITE || motivo == Motivo.INDISPONIVEL || motivo == Motivo.COTA_DIARIA;
    }
}
