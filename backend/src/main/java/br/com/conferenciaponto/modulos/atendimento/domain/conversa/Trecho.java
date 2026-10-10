package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

/**
 * Um pedaço de texto do PDF, com a posição: letras seguidas, do mesmo tamanho e na mesma linha (um espaço grande ou
 * uma troca de tamanho começa outro trecho). Coordenadas da página com o y crescendo para baixo.
 *
 * @param pagina  a partir de 1
 * @param x0      começo do trecho
 * @param x1      fim do trecho
 * @param y       linha de base do texto
 * @param tamanho tamanho da letra, como o PDF informa (só serve para comparar trechos do mesmo PDF)
 */
public record Trecho(int pagina, float x0, float x1, float y, float tamanho, String texto) {

    public Trecho {
        texto = texto == null ? "" : texto;
    }

    public float centro() {
        return (x0 + x1) / 2;
    }

    /** O texto não vai para o toString: pode ser conteúdo de conversa de cliente (e o toString acaba em log). */
    @Override
    public String toString() {
        return "Trecho[pagina=" + pagina + ", x0=" + x0 + ", x1=" + x1 + ", y=" + y + ", tamanho=" + tamanho
                + ", caracteres=" + texto.length() + "]";
    }
}
