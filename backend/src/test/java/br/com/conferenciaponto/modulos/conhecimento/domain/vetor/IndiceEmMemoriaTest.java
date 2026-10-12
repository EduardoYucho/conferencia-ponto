package br.com.conferenciaponto.modulos.conhecimento.domain.vetor;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Vetores com ordem conhecida: o mais parecido primeiro, o limiar, o máximo e o filtro. */
class IndiceEmMemoriaTest {

    private final UUID a = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private final UUID c = UUID.fromString("00000000-0000-4000-8000-00000000000c");

    private IndiceEmMemoria indice() {
        IndiceEmMemoria indice = new IndiceEmMemoria("m", 3);
        indice.colocar(a, new float[]{1, 0, 0});
        indice.colocar(b, new float[]{0.8f, 0.6f, 0});
        indice.colocar(c, new float[]{0, 0, 5}); // normalizado ao entrar
        return indice;
    }

    @Test
    void doMaisParaOMenosParecido() {
        var achados = indice().maisParecidos(new float[]{2, 0, 0}, 0.0, 10, null);

        assertThat(achados).extracting(Semelhante::registroId).containsExactly(a, b, c);
        assertThat(achados.get(0).similaridade()).isCloseTo(1.0, within(1e-6));
        assertThat(achados.get(1).similaridade()).isCloseTo(0.8, within(1e-6));
        assertThat(achados.get(2).similaridade()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void limiarMaximoEFiltro() {
        IndiceEmMemoria indice = indice();

        assertThat(indice.maisParecidos(new float[]{1, 0, 0}, 0.75, 10, null)).extracting(Semelhante::registroId).containsExactly(a, b);
        assertThat(indice.maisParecidos(new float[]{1, 0, 0}, 0.0, 1, null)).extracting(Semelhante::registroId).containsExactly(a);
        assertThat(indice.maisParecidos(new float[]{1, 0, 0}, 0.0, 10, id -> !id.equals(a)))
                .extracting(Semelhante::registroId).containsExactly(b, c);
        assertThat(indice.maisParecidos(new float[]{1, 0}, 0.0, 10, null)).as("outro tamanho").isEmpty();
    }

    @Test
    void tirarETamanho() {
        IndiceEmMemoria indice = indice();
        indice.tirar(a);

        assertThat(indice.tamanho()).isEqualTo(2);
        assertThat(indice.tem(a)).isFalse();
        assertThatThrownBy(() -> indice.colocar(a, new float[]{1, 0})).isInstanceOf(IllegalArgumentException.class);
    }
}
