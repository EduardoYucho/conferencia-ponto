package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

/**
 * Um link clicável do PDF (no Digisac, cada anexo da conversa é um link assinado para o armazenamento dele).
 * Retângulo em coordenadas da página com o y crescendo para baixo ({@code y0} em cima).
 */
public record LinkDoPdf(int pagina, float x0, float x1, float y0, float y1, String url) {

    public boolean contem(Trecho trecho, float folga) {
        return trecho.pagina() == pagina && trecho.x0() >= x0 - folga && trecho.x1() <= x1 + folga
                && trecho.y() >= y0 - folga && trecho.y() <= y1 + folga;
    }

    /** A URL é assinada (dá acesso ao arquivo por 24 h): nunca vai para o toString nem para o log. */
    @Override
    public String toString() {
        return "LinkDoPdf[pagina=" + pagina + ", x0=" + x0 + ", x1=" + x1 + ", y0=" + y0 + ", y1=" + y1 + "]";
    }
}
