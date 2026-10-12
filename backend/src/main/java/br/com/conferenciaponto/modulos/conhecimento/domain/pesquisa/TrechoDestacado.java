package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monta o {@link Trecho} a partir do destaque do PostgreSQL (ts_headline). A busca roda no texto sem acento, então o
 * destaque também vem sem acento: aqui as marcas voltam para o texto original, com os acentos. O unaccent troca quase
 * sempre uma letra por outra ("ç" → "c"); quando troca por mais de uma ("…" → "..."), o alinhamento acompanha.
 */
public final class TrechoDestacado {

    /** O que o ts_headline põe em volta do que bateu e entre os fragmentos (caracteres que não aparecem em texto). */
    public static final char INICIO = '\u0001';
    public static final char FIM = '\u0002';
    public static final char ENTRE_FRAGMENTOS = '\u0003';
    public static final String RETICENCIAS = "…";

    private TrechoDestacado() {
    }

    /**
     * @param original  o texto como está no registro
     * @param semAcento o mesmo texto depois do unaccent do banco
     * @param destacado o ts_headline sobre o texto sem acento, com {@link #INICIO}, {@link #FIM} e
     *                  {@link #ENTRE_FRAGMENTOS}
     */
    public static Trecho de(String original, String semAcento, String destacado) {
        if (original == null || original.isEmpty()) {
            return Trecho.simples(null);
        }
        if (semAcento == null || destacado == null) {
            return Trecho.simples(original);
        }
        int[] mapa = mapa(original, semAcento);
        List<Trecho.Parte> partes = new ArrayList<>();
        int procurarDesde = 0;
        int fimAnterior = 0;
        boolean primeiro = true;
        for (String fragmento : destacado.split(String.valueOf(ENTRE_FRAGMENTOS), -1)) {
            Marcado marcado = Marcado.ler(fragmento);
            String limpo = marcado.texto();
            if (limpo.isBlank()) {
                continue;
            }
            int inicio = semAcento.indexOf(limpo, procurarDesde);
            if (inicio < 0) {
                inicio = semAcento.indexOf(limpo);
            }
            if (inicio < 0) {
                return semMapa(destacado); // o destaque não bate com o texto: fica sem acento, mas com as marcas
            }
            int fim = inicio + limpo.length();
            if (inicio > fimAnterior || (primeiro && inicio > 0)) {
                partes.add(new Trecho.Parte(primeiro ? RETICENCIAS + " " : " " + RETICENCIAS + " ", false));
            }
            int cursor = mapa[inicio];
            for (int[] marca : marcado.marcas()) {
                int de = mapa[inicio + marca[0]];
                int ate = mapa[inicio + marca[1]];
                if (de > cursor) {
                    partes.add(new Trecho.Parte(original.substring(cursor, de), false));
                }
                if (ate > de) {
                    partes.add(new Trecho.Parte(original.substring(de, ate), true));
                }
                cursor = Math.max(cursor, ate);
            }
            int fimNoOriginal = mapa[fim];
            if (fimNoOriginal > cursor) {
                partes.add(new Trecho.Parte(original.substring(cursor, fimNoOriginal), false));
            }
            procurarDesde = fim;
            fimAnterior = fim;
            primeiro = false;
        }
        if (partes.isEmpty()) {
            return Trecho.simples(original);
        }
        if (fimAnterior < semAcento.length() && !semAcento.substring(fimAnterior).isBlank()) {
            partes.add(new Trecho.Parte(" " + RETICENCIAS, false));
        }
        return new Trecho(juntar(partes));
    }

    /** O começo do texto, cortado numa palavra (sem pesquisa, ou quando nada bateu no trecho). */
    public static Trecho inicio(String texto, int limite) {
        if (texto == null || texto.isBlank()) {
            return Trecho.simples(null);
        }
        String t = texto.strip();
        if (t.length() <= limite) {
            return Trecho.simples(t);
        }
        int corte = t.lastIndexOf(' ', limite);
        return Trecho.simples(t.substring(0, corte > limite / 2 ? corte : limite).strip() + " " + RETICENCIAS);
    }

    /** Destaca no texto os pedaços que aparecem nele (sem diferença de maiúsculas e acentos): o código da tela. */
    public static Trecho comPedacos(String texto, List<String> pedacos) {
        if (texto == null || texto.isEmpty()) {
            return Trecho.simples(null);
        }
        String comparavel = ConsultaDeTexto.semAcento(texto).toLowerCase(Locale.ROOT);
        if (comparavel.length() != texto.length()) {
            return Trecho.simples(texto);
        }
        boolean[] marcado = new boolean[texto.length()];
        for (String pedaco : pedacos) {
            if (pedaco.isEmpty()) {
                continue;
            }
            for (int i = comparavel.indexOf(pedaco); i >= 0; i = comparavel.indexOf(pedaco, i + 1)) {
                for (int k = i; k < i + pedaco.length(); k++) {
                    marcado[k] = true;
                }
            }
        }
        List<Trecho.Parte> partes = new ArrayList<>();
        int i = 0;
        while (i < texto.length()) {
            int j = i;
            while (j < texto.length() && marcado[j] == marcado[i]) {
                j++;
            }
            partes.add(new Trecho.Parte(texto.substring(i, j), marcado[i]));
            i = j;
        }
        return new Trecho(partes);
    }

    /**
     * Para cada posição do texto sem acento, a posição correspondente no original (mais uma no fim). Com o mesmo
     * tamanho, é posição por posição; senão, alinha pulando o que o unaccent expandiu ou tirou.
     */
    static int[] mapa(String original, String semAcento) {
        int[] mapa = new int[semAcento.length() + 1];
        if (original.length() == semAcento.length()) {
            for (int k = 0; k <= semAcento.length(); k++) {
                mapa[k] = k;
            }
            return mapa;
        }
        int i = 0;
        int j = 0;
        while (j < semAcento.length()) {
            if (i >= original.length()) {
                mapa[j++] = original.length();
            } else if (igual(original.charAt(i), semAcento.charAt(j))) {
                mapa[j++] = i++;
            } else if (i + 1 < original.length() && igual(original.charAt(i + 1), semAcento.charAt(j))) {
                i++; // o unaccent tirou este caractere
            } else {
                // este caractere virou vários: todos apontam para ele, até o próximo que bate
                mapa[j++] = i;
                while (j < semAcento.length() && !(i + 1 < original.length() && igual(original.charAt(i + 1), semAcento.charAt(j)))) {
                    mapa[j++] = i;
                }
                i++;
            }
        }
        mapa[semAcento.length()] = Math.min(i, original.length());
        // as posições de dentro de um caractere expandido terminam depois dele
        return mapa;
    }

    private static boolean igual(char original, char semAcento) {
        if (original == semAcento) {
            return true;
        }
        String base = Normalizer.normalize(String.valueOf(original), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return base.length() == 1 && base.charAt(0) == semAcento;
    }

    private static Trecho semMapa(String destacado) {
        List<Trecho.Parte> partes = new ArrayList<>();
        boolean primeiro = true;
        for (String fragmento : destacado.split(String.valueOf(ENTRE_FRAGMENTOS), -1)) {
            Marcado marcado = Marcado.ler(fragmento);
            if (marcado.texto().isBlank()) {
                continue;
            }
            if (!primeiro) {
                partes.add(new Trecho.Parte(" " + RETICENCIAS + " ", false));
            }
            int cursor = 0;
            for (int[] marca : marcado.marcas()) {
                if (marca[0] > cursor) {
                    partes.add(new Trecho.Parte(marcado.texto().substring(cursor, marca[0]), false));
                }
                partes.add(new Trecho.Parte(marcado.texto().substring(marca[0], marca[1]), true));
                cursor = marca[1];
            }
            if (cursor < marcado.texto().length()) {
                partes.add(new Trecho.Parte(marcado.texto().substring(cursor), false));
            }
            primeiro = false;
        }
        return new Trecho(juntar(partes));
    }

    /** Partes vizinhas do mesmo jeito viram uma só. */
    private static List<Trecho.Parte> juntar(List<Trecho.Parte> partes) {
        List<Trecho.Parte> juntas = new ArrayList<>();
        for (Trecho.Parte parte : partes) {
            if (parte.texto().isEmpty()) {
                continue;
            }
            Trecho.Parte ultima = juntas.isEmpty() ? null : juntas.get(juntas.size() - 1);
            if (ultima != null && ultima.destaque() == parte.destaque()) {
                juntas.set(juntas.size() - 1, new Trecho.Parte(ultima.texto() + parte.texto(), parte.destaque()));
            } else {
                juntas.add(parte);
            }
        }
        return juntas;
    }

    /** Um fragmento sem as marcas, com onde elas estavam ([início, fim) no texto limpo). */
    private record Marcado(String texto, List<int[]> marcas) {

        static Marcado ler(String fragmento) {
            StringBuilder limpo = new StringBuilder();
            List<int[]> marcas = new ArrayList<>();
            int abriu = -1;
            for (int k = 0; k < fragmento.length(); k++) {
                char c = fragmento.charAt(k);
                if (c == INICIO) {
                    abriu = limpo.length();
                } else if (c == FIM) {
                    if (abriu >= 0) {
                        marcas.add(new int[]{abriu, limpo.length()});
                    }
                    abriu = -1;
                } else {
                    limpo.append(c);
                }
            }
            return new Marcado(limpo.toString(), marcas);
        }
    }
}
