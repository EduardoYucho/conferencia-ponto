package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * O que a pessoa digitou na pesquisa, pronto para o banco: as palavras sem acento e em minúsculas (só letras e
 * números, então nada do texto vira operador da busca) e as consultas do PostgreSQL.
 *
 * <p>A busca textual guarda o texto sem acento e com o radical do português, e o radical das palavras em -ão e -ões
 * fica diferente sem o acento ("configuracao" → configuraca, "configuracoes" → configuraco). Por isso cada palavra
 * terminada assim leva também as outras formas: quem procura "configurações" acha "configuração".
 */
public final class ConsultaDeTexto {

    public static final int MAXIMO_DE_PALAVRAS = 12;
    public static final int MAXIMO_DO_TERMO = 300;

    private final String termo;
    private final List<String> palavras;

    private ConsultaDeTexto(String termo, List<String> palavras) {
        this.termo = termo;
        this.palavras = palavras;
    }

    public static ConsultaDeTexto de(String texto) {
        String termo = texto == null ? "" : texto.replaceAll("\\s+", " ").strip();
        if (termo.length() > MAXIMO_DO_TERMO) {
            termo = termo.substring(0, MAXIMO_DO_TERMO);
        }
        Set<String> palavras = new LinkedHashSet<>();
        for (String p : semAcento(termo).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (p.length() >= 2 && palavras.size() < MAXIMO_DE_PALAVRAS) {
                palavras.add(p);
            }
        }
        return new ConsultaDeTexto(termo, List.copyOf(palavras));
    }

    /** Nada que dê para procurar (vazio ou só sinais). */
    public boolean vazia() {
        return palavras.isEmpty();
    }

    /** O texto inteiro como foi digitado (espaços normalizados), para os trigramas. */
    public String termo() {
        return termo;
    }

    public List<String> palavras() {
        return palavras;
    }

    /** Todas as palavras (cada uma com as formas dela): {@code (configuracao | configuracoes) & usuario}. */
    public String todas() {
        return palavras.stream()
                .map(p -> {
                    List<String> formas = variantes(p);
                    return formas.size() == 1 ? formas.get(0) : "(" + String.join(" | ", formas) + ")";
                })
                .collect(Collectors.joining(" & "));
    }

    /** Qualquer uma das palavras, para destacar no trecho tudo o que bateu. */
    public String qualquer() {
        return palavras.stream().flatMap(p -> variantes(p).stream()).distinct().collect(Collectors.joining(" | "));
    }

    /** As palavras que podem ser um pedaço de código de tela ({@code eqr7} acha EQR76). */
    public List<String> pedacosDeCodigo() {
        return palavras.stream().filter(p -> p.length() >= 3).toList();
    }

    /** A palavra e as formas dela no singular e no plural, quando termina em -ão/-ões/-ães/-ãos. */
    static List<String> variantes(String palavra) {
        Set<String> formas = new LinkedHashSet<>();
        formas.add(palavra);
        if (palavra.length() >= 4) {
            if (palavra.endsWith("oes") || palavra.endsWith("aes") || palavra.endsWith("aos")) {
                String raiz = palavra.substring(0, palavra.length() - 3);
                formas.add(raiz + "ao");
            } else if (palavra.endsWith("ao")) {
                String raiz = palavra.substring(0, palavra.length() - 2);
                formas.add(raiz + "oes");
                formas.add(raiz + "aes");
                formas.add(raiz + "aos");
            }
        }
        return new ArrayList<>(formas);
    }

    /** Sem acento (só para separar as palavras; o banco usa o unaccent dele). */
    static String semAcento(String texto) {
        String decomposto = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposto.replace("ß", "ss").replace("æ", "ae").replace("Æ", "AE").replace("ø", "o").replace("Ø", "O")
                .replace("œ", "oe").replace("Œ", "OE");
    }

    @Override
    public String toString() {
        return "ConsultaDeTexto" + palavras;
    }
}
