package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatidasTest {

    private static final LocalTime H08 = LocalTime.of(8, 0);
    private static final LocalTime H12 = LocalTime.of(12, 0);
    private static final LocalTime H13 = LocalTime.of(13, 0);

    @Test
    @DisplayName("Descarta frações de segundo (coluna TIME(0))")
    void truncaFracoes() {
        Batidas b = new Batidas(LocalTime.of(8, 2, 31, 900_000_000), null, null, null);
        assertThat(b.entrada1()).isEqualTo(LocalTime.of(8, 2, 31));
    }

    @Test
    @DisplayName("Rejeita batida fora de sequência")
    void rejeitaForaDeSequencia() {
        assertThatThrownBy(() -> new Batidas(null, H12, null, null))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("BATIDA_FORA_DE_SEQUENCIA");
    }

    @Test
    @DisplayName("Rejeita saída anterior à entrada")
    void rejeitaForaDeOrdem() {
        assertThatThrownBy(() -> new Batidas(H12, H08, null, null))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("BATIDA_FORA_DE_ORDEM");
    }

    @Test
    @DisplayName("Aceita Entrada 2 igual à Saída 1")
    void aceitaIntervaloZero() {
        Batidas b = new Batidas(H08, H12, H12, H13);
        assertThat(b.quantidade()).isEqualTo(4);
    }

    @Test
    @DisplayName("Registra batidas na ordem e bloqueia a 7ª (até 3 intervalos)")
    void registraNaOrdem() {
        Batidas b = Batidas.vazia().comBatida(H08).comBatida(H12);
        assertThat(b.proximaBatida()).contains(TipoBatida.ENTRADA_2);
        assertThat(b.isJornadaAberta()).isFalse();

        b = b.comBatida(H13);
        assertThat(b.isJornadaAberta()).isTrue();

        Batidas quatro = b.comBatida(LocalTime.of(17, 48));
        assertThat(quatro.proximaBatida()).contains(TipoBatida.ENTRADA_3);
        Batidas completa = quatro.comBatida(LocalTime.of(18, 30)).comBatida(LocalTime.of(20, 0));
        assertThat(completa.saida3()).isEqualTo(LocalTime.of(20, 0));
        assertThat(completa.proximaBatida()).isEmpty();
        assertThatThrownBy(() -> completa.comBatida(LocalTime.of(21, 0)))
                .extracting("codigo").isEqualTo("JORNADA_COMPLETA");
    }

    @Test
    @DisplayName("Inserção ordenada reorganiza as colunas e rejeita duplicata/7ª batida")
    void insercaoOrdenada() {
        Batidas b = Batidas.vazia()
                .comBatidaOrdenada(H13)
                .comBatidaOrdenada(H08)
                .comBatidaOrdenada(H12);

        assertThat(b.entrada1()).isEqualTo(H08);
        assertThat(b.saida1()).isEqualTo(H12);
        assertThat(b.entrada2()).isEqualTo(H13);
        assertThat(b.isJornadaAberta()).isTrue();

        assertThatThrownBy(() -> b.comBatidaOrdenada(H12))
                .extracting("codigo").isEqualTo("BATIDA_DUPLICADA");

        Batidas completa = b.comBatidaOrdenada(LocalTime.of(17, 48))
                .comBatidaOrdenada(LocalTime.of(11, 0))   // saída extra no meio da manhã
                .comBatidaOrdenada(LocalTime.of(11, 15));
        assertThat(completa.horarios()).containsExactly(H08, LocalTime.of(11, 0), LocalTime.of(11, 15), H12, H13,
                LocalTime.of(17, 48));
        assertThatThrownBy(() -> completa.comBatidaOrdenada(LocalTime.of(19, 0)))
                .extracting("codigo").isEqualTo("JORNADA_COMPLETA");
    }

    @Test
    @DisplayName("Localiza batida próxima dentro da janela")
    void batidaProxima() {
        Batidas b = new Batidas(LocalTime.of(8, 2, 50), null, null, null);
        assertThat(b.batidaProxima(LocalTime.of(8, 2, 31), java.time.Duration.ofMinutes(1)))
                .contains(LocalTime.of(8, 2, 50));
        assertThat(b.batidaProxima(LocalTime.of(8, 4, 0), java.time.Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    @DisplayName("Lançamento manual aceita de 1 a 2 intervalos")
    void intervalos() {
        Batidas b = Batidas.deIntervalos(List.of(new Intervalo(H08, H12), new Intervalo(H13, LocalTime.of(15, 0))));
        assertThat(b.saida2()).isEqualTo(LocalTime.of(15, 0));

        assertThatThrownBy(() -> Batidas.deIntervalos(List.of()))
                .extracting("codigo").isEqualTo("QUANTIDADE_INTERVALOS_INVALIDA");
        assertThatThrownBy(() -> new Intervalo(H12, H08))
                .extracting("codigo").isEqualTo("INTERVALO_INVALIDO");
    }
}
