package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import java.util.List;

/**
 * Uma página de resultados.
 *
 * @param comTexto     a pesquisa tinha texto (a ordem é do que mais bateu); sem texto, do mais novo para o mais antigo
 * @param significado  como ficou a pesquisa por significado nesta pesquisa
 */
public record ResultadoDaPesquisa(List<RegistroEncontrado> registros, long total, int pagina, int paginas, int tamanho,
                                  boolean comTexto, Significado significado) {

    public ResultadoDaPesquisa(List<RegistroEncontrado> registros, long total, int pagina, int paginas, int tamanho,
                               boolean comTexto) {
        this(registros, total, pagina, paginas, tamanho, comTexto, null);
    }

    /**
     * @param ativa      a pesquisa por significado entrou nesta pesquisa
     * @param aviso      por que ficou só a exata (sem chave, Gemini fora...), ou null
     * @param pendentes  quantos registros ainda estão sem vetor (aparecem só na pesquisa exata)
     */
    public record Significado(boolean ativa, String aviso, long pendentes) {
    }
}
