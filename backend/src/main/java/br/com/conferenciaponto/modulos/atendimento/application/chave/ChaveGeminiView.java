package br.com.conferenciaponto.modulos.atendimento.application.chave;

import java.time.Instant;

/**
 * A chave do Gemini como a tela vê: nunca a chave, só os últimos caracteres.
 *
 * @param situacao        nao_testada, valida, recusada, sem_cota (null se não há chave)
 * @param exigirNivelPago o cadastro pede a confirmação de que a chave é de projeto com faturamento
 * @param mensagem        o que o último teste disse (só na resposta do teste)
 */
public record ChaveGeminiView(boolean cadastrada, String ultimosCaracteres, boolean nivelPagoConfirmado, String situacao,
                              Instant testadaEm, Instant atualizadaEm, boolean exigirNivelPago, String mensagem) {
}
