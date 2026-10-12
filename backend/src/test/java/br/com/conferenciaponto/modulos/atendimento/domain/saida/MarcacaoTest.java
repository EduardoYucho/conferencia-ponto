package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** As caixas da análise (de 0 a 1000) viram retângulos em pixels, com folga e sem sair da imagem. */
class MarcacaoTest {

    @Test
    void soAsCaixasDentroDaEscalaEComTamanho() {
        assertThat(new Marcacao("erro", 100, 200, 300, 400).valida()).isTrue();
        assertThat(new Marcacao("tudo", 0, 0, 1000, 1000).valida()).isTrue();
        assertThat(new Marcacao("invertida", 300, 200, 100, 400).valida()).isFalse();
        assertThat(new Marcacao("sem largura", 100, 200, 300, 200).valida()).isFalse();
        assertThat(new Marcacao("fora", 100, 200, 300, 1001).valida()).isFalse();
        assertThat(new Marcacao("negativa", -1, 200, 300, 400).valida()).isFalse();
    }

    @Test
    void convertePelaLarguraEPelaAlturaDaImagem() {
        // ymin, xmin, ymax, xmax: a ordem que o Gemini usa
        Marcacao.Retangulo r = new Marcacao("campo", 100, 250, 200, 500).emPixels(800, 600, 0);

        assertThat(r).isEqualTo(new Marcacao.Retangulo(200, 60, 200, 60));
    }

    @Test
    void aFolgaNaoSaiDaImagem() {
        Marcacao.Retangulo noCanto = new Marcacao("canto", 0, 0, 1000, 1000).emPixels(800, 600, 5);
        assertThat(noCanto).isEqualTo(new Marcacao.Retangulo(0, 0, 799, 599));

        Marcacao.Retangulo comFolga = new Marcacao("meio", 500, 500, 510, 510).emPixels(1000, 1000, 5);
        assertThat(comFolga).isEqualTo(new Marcacao.Retangulo(495, 495, 20, 20));
    }

    @Test
    void caixaMinusculaAindaTemUmPixel() {
        Marcacao.Retangulo r = new Marcacao("ponto", 1000, 1000, 1000, 1000).emPixels(10, 10, 0);

        assertThat(r.largura()).isEqualTo(1);
        assertThat(r.altura()).isEqualTo(1);
    }
}
