package br.com.conferenciaponto.modulos.atendimento.domain.ia;

/**
 * O texto que o modelo devolveu (o JSON pedido) e o que a chamada gastou.
 *
 * @param tokensSaida inclui os tokens de raciocínio, que são cobrados como saída
 */
public record RespostaDoModelo(String texto, int tokensEntrada, int tokensSaida) {
}
