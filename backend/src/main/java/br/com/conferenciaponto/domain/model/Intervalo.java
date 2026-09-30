package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.LocalTime;

/**
 * Par entrada/saída informado em lançamentos manuais.
 */
public record Intervalo(LocalTime entrada, LocalTime saida) {

    public Intervalo {
        if (entrada == null || saida == null) {
            throw new RegraNegocioException("INTERVALO_INCOMPLETO",
                    "Informe entrada e saída para cada intervalo.");
        }
        if (!saida.isAfter(entrada)) {
            throw new RegraNegocioException("INTERVALO_INVALIDO",
                    "A saída (%s) deve ser posterior à entrada (%s).".formatted(saida, entrada));
        }
    }
}
