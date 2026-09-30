package br.com.conferenciaponto.domain.model;

import java.time.LocalTime;

/**
 * Resultado da apuração de uma única batida.
 *
 * @param tipo               posição da batida
 * @param oficial            horário da grade a que a batida corresponde (null em dias não úteis e em
 *                           batidas extras, como a saída/volta de um compromisso no meio do expediente)
 * @param real               horário efetivamente batido, com segundos (null se não houve batida)
 * @param considerado        horário usado no cálculo: o oficial, se tolerado; senão, o real
 * @param desvioSegundos     real - oficial, em segundos (positivo = depois do oficial); null se não aplicável
 * @param toleranciaAplicada true quando o horário oficial substituiu o real
 */
public record MarcacaoApurada(
        TipoBatida tipo,
        LocalTime oficial,
        LocalTime real,
        LocalTime considerado,
        Integer desvioSegundos,
        boolean toleranciaAplicada) {
}
