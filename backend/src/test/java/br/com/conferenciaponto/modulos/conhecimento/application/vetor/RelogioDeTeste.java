package br.com.conferenciaponto.modulos.conhecimento.application.vetor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Um relógio que o teste adianta. */
public final class RelogioDeTeste extends Clock {

    private volatile Instant agora;
    private final ZoneId fuso;

    public RelogioDeTeste(Instant agora, ZoneId fuso) {
        this.agora = agora;
        this.fuso = fuso;
    }

    public void adiantar(Duration quanto) {
        agora = agora.plus(quanto);
    }

    @Override
    public ZoneId getZone() {
        return fuso;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new RelogioDeTeste(agora, zone);
    }

    @Override
    public Instant instant() {
        return agora;
    }
}
