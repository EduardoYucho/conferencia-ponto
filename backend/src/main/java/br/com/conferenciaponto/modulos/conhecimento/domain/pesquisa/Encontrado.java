package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registro;

/**
 * Um registro que bateu com a pesquisa, com o destaque do título e de um trecho (sintoma, erro, causa e resolução).
 *
 * @param relevancia quanto bateu (só para a ordem)
 */
public record Encontrado(Registro registro, double relevancia, Destaque titulo, Destaque trecho) {

    /**
     * O destaque como veio do banco.
     *
     * @param destacado o ts_headline do texto sem acento (null quando não houve texto na pesquisa)
     */
    public record Destaque(String original, String semAcento, String destacado) {
    }
}
