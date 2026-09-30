package br.com.conferenciaponto.infrastructure.web.dto;

/**
 * @param codigo   identificador estável do erro (ex.: LANCAMENTO_MANUAL_DIA_UTIL)
 * @param mensagem texto para exibição ao usuário
 * @param campo    campo do payload relacionado (validação), ou null
 */
public record ApiErro(String codigo, String mensagem, String campo) {
}
