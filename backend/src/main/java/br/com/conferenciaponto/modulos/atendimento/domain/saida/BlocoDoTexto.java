package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Um parágrafo ou um passo do texto, com as fontes (os identificadores da linha do tempo em que se baseia) e as
 * imagens que vêm logo depois dele.
 */
public record BlocoDoTexto(Tipo tipo, String texto, List<String> fontes, List<ImagemCitada> imagens) {

    public enum Tipo {
        PARAGRAFO, PASSO;

        public String codigo() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** O que vier diferente de "passo" é parágrafo. */
        public static Tipo doCodigo(String codigo) {
            return codigo != null && codigo.strip().equalsIgnoreCase("passo") ? PASSO : PARAGRAFO;
        }
    }

    public BlocoDoTexto {
        texto = texto == null ? "" : texto;
        fontes = fontes == null ? List.of() : List.copyOf(fontes);
        imagens = imagens == null ? List.of() : List.copyOf(imagens);
    }

    /**
     * Uma imagem citada no texto.
     *
     * @param id     o identificador na linha do tempo (ex.: A3)
     * @param imagem o id da imagem da saída (vai no HTML)
     * @param numero "Imagem N"
     */
    public record ImagemCitada(String id, UUID imagem, int numero, String legenda) {
    }
}
