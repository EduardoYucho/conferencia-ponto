package br.com.conferenciaponto.modulos.conhecimento.domain.vetor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * O texto que vira vetor, no formato que o gemini-embedding-2 pede para cada uso: o registro como documento
 * ({@code title: ... | text: ...}) e a pesquisa como consulta ({@code task: search result | query: ...}). O
 * documento é o título, o sintoma, a mensagem de erro, a causa e a resolução. O sha-256 do texto fica com o vetor:
 * uma edição que muda o texto deixa o vetor velho e o registro volta a ficar pendente.
 */
public final class TextoDoVetor {

    /** O tamanho máximo do texto enviado (o modelo aceita uns 8 mil tokens; um registro é bem menor). */
    public static final int MAXIMO = 12_000;

    private TextoDoVetor() {
    }

    /** O registro (ou um caso novo, no aviso de caso parecido) como documento. */
    public static String documento(String titulo, String sintoma, String mensagemErro, String causa, String resolucao) {
        String corpo = Stream.of(sintoma, mensagemErro, causa, resolucao)
                .map(TextoDoVetor::limpo)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        String t = limpo(titulo);
        return cortar("title: " + (t == null ? "none" : t.replace('|', '/')) + " | text: " + (corpo.isEmpty() ? (t == null ? "" : t) : corpo));
    }

    /** O que a pessoa digitou na pesquisa. */
    public static String consulta(String texto) {
        String t = limpo(texto);
        return t == null ? null : cortar("task: search result | query: " + t);
    }

    public static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** O texto sem espaços sobrando, ou null se vazio ou "não identificado". */
    public static String limpoOuNulo(String texto) {
        return limpo(texto);
    }

    /** Vazio e "não identificado" não entram (nem as linhas assim, num texto de várias linhas). */
    static String limpo(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.replace("\r\n", "\n").lines()
                .map(l -> l.replaceAll("[ \\t]+", " ").strip())
                .filter(l -> !l.isEmpty() && !naoIdentificado(l))
                .collect(Collectors.joining("\n"));
        return limpo.isEmpty() ? null : limpo;
    }

    private static boolean naoIdentificado(String linha) {
        String minusculo = linha.toLowerCase(Locale.ROOT).replaceAll("[.\\s]+$", "");
        return minusculo.equals("não identificado") || minusculo.equals("nao identificado") || minusculo.equals("não informado");
    }

    private static String cortar(String texto) {
        return texto.length() <= MAXIMO ? texto : texto.substring(0, MAXIMO);
    }
}
