package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.analise.SituacaoDaAnalise;

/**
 * A análise de um arquivo para a tela.
 *
 * @param codigo   o código do erro quando falhou (ex.: SEM_CHAVE_GEMINI), ou o da espera (GEMINI_LIMITE)
 * @param mensagem o motivo para a pessoa ler, ou null
 */
public record EstadoDaAnalise(SituacaoDaAnalise situacao, String codigo, String mensagem) {
}
