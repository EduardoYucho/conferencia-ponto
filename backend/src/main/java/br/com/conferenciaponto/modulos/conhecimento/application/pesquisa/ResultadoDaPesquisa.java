package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import java.util.List;

/**
 * Uma página de resultados.
 *
 * @param comTexto a pesquisa tinha texto (a ordem é do que mais bateu); sem texto, do mais novo para o mais antigo
 */
public record ResultadoDaPesquisa(List<RegistroEncontrado> registros, long total, int pagina, int paginas, int tamanho,
                                  boolean comTexto) {
}
