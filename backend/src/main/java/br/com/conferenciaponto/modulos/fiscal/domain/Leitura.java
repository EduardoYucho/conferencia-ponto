package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Uma leitura de um alvo numa rodada.
 *
 * @param detalhe      o que a fonte disse ("WS com status normal", "respondeu em 420 ms", "não respondeu em 15 s")
 * @param cstat        o código de retorno publicado pelo painel (ex.: 107); vazio no teste de conexão
 * @param tempoMs      quanto o teste de conexão levou; vazio no painel
 * @param verificadoEm quando a fonte verificou (o horário do painel); vazio no teste de conexão
 */
public record Leitura(Alvo alvo, Origem origem, Situacao situacao, String detalhe, Integer cstat, Integer tempoMs,
                      Instant verificadoEm) {

    public Leitura {
        Objects.requireNonNull(alvo, "alvo");
        Objects.requireNonNull(origem, "origem");
        Objects.requireNonNull(situacao, "situacao");
        detalhe = cortar(detalhe, 300);
    }

    /** A mesma leitura, sem informação (ex.: sem internet neste computador). */
    public Leitura semInformacao(String motivo) {
        return new Leitura(alvo, origem, Situacao.SEM_INFORMACAO, motivo, null, null, verificadoEm);
    }

    static String cortar(String texto, int maximo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String limpo = texto.strip().replaceAll("\\s+", " ");
        return limpo.length() <= maximo ? limpo : limpo.substring(0, maximo - 1) + "…";
    }
}
