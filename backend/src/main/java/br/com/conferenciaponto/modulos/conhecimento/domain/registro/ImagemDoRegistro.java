package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

/**
 * Uma imagem guardada com o registro (cópia da que estava no texto confirmado).
 *
 * @param numero  o "Imagem N" do texto
 * @param caminho relativo à pasta dos módulos (base/&lt;id do registro&gt;/Imagem_01.png)
 */
public record ImagemDoRegistro(int numero, String caminho, String legenda) {
}
