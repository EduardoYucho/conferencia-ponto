package br.com.conferenciaponto.infrastructure.web.dto;

/**
 * Erro que aconteceu no navegador.
 *
 * @param mensagem o que foi mostrado (ou o texto do erro)
 * @param tela     em que tela estava (rota)
 * @param detalhe  detalhe técnico, se houver (pilha do erro)
 */
public record ErroDeTelaRequest(String mensagem, String tela, String detalhe) {
}
