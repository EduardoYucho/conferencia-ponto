package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A pesquisa como vem da tela (tudo opcional).
 *
 * @param q        o texto: palavras, código de tela (ou um pedaço), mensagem de erro, cliente
 * @param de       criados a partir deste dia (no fuso do sistema)
 * @param ate      criados até este dia (inclusive)
 * @param pagina   a partir de 1
 */
public record FiltrosDaPesquisa(String q, String tipo, String situacao, String cliente, String modulo, UUID autor,
                                LocalDate de, LocalDate ate, boolean validadas, Integer pagina) {
}
