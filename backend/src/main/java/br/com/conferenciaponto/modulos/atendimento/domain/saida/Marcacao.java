package br.com.conferenciaponto.modulos.atendimento.domain.saida;

/**
 * Uma área de interesse que a análise da imagem apontou (a mensagem de erro, o campo com problema), na escala que o
 * Gemini usa: [ymin, xmin, ymax, xmax] de 0 a 1000, independente do tamanho da imagem.
 */
public record Marcacao(String descricao, int ymin, int xmin, int ymax, int xmax) {

    public static final int ESCALA = 1000;

    /** Uma caixa que dá para desenhar: dentro da escala e com altura e largura. */
    public boolean valida() {
        return dentro(ymin) && dentro(xmin) && dentro(ymax) && dentro(xmax) && ymax > ymin && xmax > xmin;
    }

    /**
     * O retângulo em pixels numa imagem desse tamanho, com uma folga em volta (a borda não cobre o que está
     * marcado) e sem sair da imagem.
     */
    public Retangulo emPixels(int largura, int altura, int folga) {
        int x1 = (int) Math.floor((double) xmin * largura / ESCALA) - folga;
        int y1 = (int) Math.floor((double) ymin * altura / ESCALA) - folga;
        int x2 = (int) Math.ceil((double) xmax * largura / ESCALA) + folga;
        int y2 = (int) Math.ceil((double) ymax * altura / ESCALA) + folga;
        x1 = limitar(x1, 0, largura - 1);
        y1 = limitar(y1, 0, altura - 1);
        x2 = limitar(x2, 0, largura - 1);
        y2 = limitar(y2, 0, altura - 1);
        return new Retangulo(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
    }

    private static boolean dentro(int v) {
        return v >= 0 && v <= ESCALA;
    }

    private static int limitar(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Em pixels: o canto de cima à esquerda, a largura e a altura. */
    public record Retangulo(int x, int y, int largura, int altura) {
    }
}
