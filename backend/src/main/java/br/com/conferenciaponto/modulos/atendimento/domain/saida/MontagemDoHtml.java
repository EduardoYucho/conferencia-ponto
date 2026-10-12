package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.List;

/**
 * Monta o HTML de uma saída a partir dos blocos conferidos, com todo texto escapado (nada do que vem do Gemini vira
 * marcação). Formato:
 *
 * <pre>
 * &lt;p data-fontes="M2 A1"&gt;parágrafo&lt;/p&gt;
 * &lt;p data-imagem="&lt;id&gt;"&gt;&lt;strong&gt;Imagem 1&lt;/strong&gt;: legenda&lt;/p&gt;
 * &lt;ol&gt;
 * &lt;li data-fontes="M5"&gt;passo&lt;p data-imagem="&lt;id&gt;"&gt;&lt;strong&gt;Imagem 2&lt;/strong&gt;: legenda&lt;/p&gt;&lt;/li&gt;
 * &lt;/ol&gt;
 * </pre>
 *
 * Os passos seguidos formam uma lista numerada; a imagem fica logo depois do parágrafo ou do passo que ela ilustra.
 * Os atributos {@code data-*} servem à tela (fontes de cada trecho, posição de cada imagem) e saem na cópia.
 */
public final class MontagemDoHtml {

    private MontagemDoHtml() {
    }

    public static String montar(List<BlocoDoTexto> blocos) {
        StringBuilder html = new StringBuilder();
        boolean emLista = false;
        for (BlocoDoTexto bloco : blocos) {
            boolean passo = bloco.tipo() == BlocoDoTexto.Tipo.PASSO;
            if (passo && !emLista) {
                html.append("<ol>\n");
                emLista = true;
            } else if (!passo && emLista) {
                html.append("</ol>\n");
                emLista = false;
            }
            String fontes = escapar(String.join(" ", bloco.fontes()));
            if (passo) {
                html.append("<li data-fontes=\"").append(fontes).append("\">").append(texto(bloco.texto()));
                bloco.imagens().forEach(i -> html.append(imagem(i)));
                html.append("</li>\n");
            } else {
                if (!bloco.texto().isBlank()) {
                    html.append("<p data-fontes=\"").append(fontes).append("\">").append(texto(bloco.texto())).append("</p>\n");
                }
                bloco.imagens().forEach(i -> html.append(imagem(i)).append('\n'));
            }
        }
        if (emLista) {
            html.append("</ol>\n");
        }
        return html.toString().stripTrailing();
    }

    /** O conteúdo do parágrafo de uma imagem: {@code <strong>Imagem N</strong>: legenda}. */
    public static String rotulo(int numero, String legenda) {
        String base = "<strong>Imagem " + numero + "</strong>";
        return legenda == null || legenda.isBlank() ? base : base + ": " + escapar(legenda.strip());
    }

    public static String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder saida = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '&' -> saida.append("&amp;");
                case '<' -> saida.append("&lt;");
                case '>' -> saida.append("&gt;");
                case '"' -> saida.append("&quot;");
                case '\'' -> saida.append("&#39;");
                default -> saida.append(c);
            }
        }
        return saida.toString();
    }

    private static String imagem(BlocoDoTexto.ImagemCitada imagem) {
        return "<p data-imagem=\"" + imagem.imagem() + "\">" + rotulo(imagem.numero(), imagem.legenda()) + "</p>";
    }

    /** Texto escapado; as quebras de linha viram {@code <br>}. */
    private static String texto(String texto) {
        return escapar(texto.strip()).replace("\r\n", "\n").replace("\n", "<br>");
    }
}
