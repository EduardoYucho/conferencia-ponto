package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Um período em que um autorizador ficou instável ou fora: começa na primeira leitura ruim e termina na primeira
 * leitura normal depois dela. Leituras sem informação (fonte com problema, sem internet) não abrem nem fecham.
 *
 * @param id       vazio enquanto não foi gravado
 * @param fim      vazio = em andamento
 * @param pior     a situação mais grave do período
 * @param detalhe  o que a fonte disse na leitura mais grave
 * @param leituras quantas leituras ruins o período teve
 */
public record Incidente(Long id, Alvo alvo, Instant inicio, Instant fim, Situacao pior, String detalhe, int leituras) {

    public static Incidente abrir(Leitura leitura, Instant em) {
        return new Incidente(null, leitura.alvo(), em, null, leitura.situacao(), leitura.detalhe(), 1);
    }

    public boolean aberto() {
        return fim == null;
    }

    /** Mais uma leitura ruim: a situação mais grave (e o que a fonte disse nela) fica. */
    public Incidente comLeitura(Leitura leitura) {
        boolean piorou = Situacao.pior(pior, leitura.situacao()) != pior;
        return new Incidente(id, alvo, inicio, fim, piorou ? leitura.situacao() : pior,
                piorou || detalhe == null ? leitura.detalhe() : detalhe, leituras + 1);
    }

    public Incidente fechado(Instant em) {
        return new Incidente(id, alvo, inicio, em, pior, detalhe, leituras);
    }

    public Duration duracao(Instant agora) {
        return Duration.between(inicio, fim != null ? fim : agora);
    }
}
