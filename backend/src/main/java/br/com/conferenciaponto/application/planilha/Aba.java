package br.com.conferenciaponto.application.planilha;

import java.util.List;

/**
 * Uma aba da planilha de conferência.
 *
 * @param id               identificador estável da aba (o Google Sheets reconhece a aba por ele, mesmo renomeada):
 *                         {@link #ID_RESUMO} ou o mês como {@code aaaamm}
 * @param linhas           todas as linhas, cada uma com {@link #colunas()} células
 * @param largurasPx       largura de cada coluna, em pixels
 * @param linhasCongeladas linhas do topo que ficam fixas ao rolar
 */
public record Aba(int id, String titulo, List<List<Celula>> linhas, List<Integer> largurasPx, int linhasCongeladas) {

    public static final int ID_RESUMO = 100;

    public Aba {
        linhas = linhas.stream().map(List::copyOf).toList();
        largurasPx = List.copyOf(largurasPx);
        for (List<Celula> linha : linhas) {
            if (linha.size() != largurasPx.size()) {
                throw new IllegalArgumentException("Linha com %d células na aba \"%s\" de %d colunas"
                        .formatted(linha.size(), titulo, largurasPx.size()));
            }
        }
    }

    public int colunas() {
        return largurasPx.size();
    }

    /** O id é de um mês ({@code aaaamm})? As abas dos meses que deixaram de ter dados são removidas. */
    public static boolean idDeMes(int id) {
        int ano = id / 100;
        int mes = id % 100;
        return ano >= 1990 && ano <= 2999 && mes >= 1 && mes <= 12;
    }

    /** Letra(s) da coluna, a partir de 0: 0 = A, 25 = Z, 26 = AA. */
    public static String letra(int coluna) {
        StringBuilder letras = new StringBuilder();
        for (int c = coluna; c >= 0; c = c / 26 - 1) {
            letras.insert(0, (char) ('A' + c % 26));
        }
        return letras.toString();
    }

    /** Referência de célula (linha e coluna a partir de 0): {@code ref(4, 7)} = "H5". */
    public static String ref(int linha, int coluna) {
        return letra(coluna) + (linha + 1);
    }

    /** Referência a uma célula desta aba a partir de outra: {@code 'Out 2026'!H5}. */
    public String refExterna(int linha, int coluna) {
        return "'" + titulo.replace("'", "''") + "'!" + ref(linha, coluna);
    }
}
