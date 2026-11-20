package br.com.receptorbackup.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FiltroDePortasTest {

    @Test
    void soAceitaHostDestaMaquina() {
        assertThat(FiltroDePortas.hostLocal("localhost:8095", 8095)).isTrue();
        assertThat(FiltroDePortas.hostLocal("127.0.0.1:8095", 8095)).isTrue();
        assertThat(FiltroDePortas.hostLocal("[::1]:8095", 8095)).isTrue();
        assertThat(FiltroDePortas.hostLocal("LOCALHOST:8095", 8095)).isTrue();
        assertThat(FiltroDePortas.hostLocal("ataque.exemplo.com:8095", 8095)).isFalse();
        assertThat(FiltroDePortas.hostLocal("localhost:8096", 8095)).isFalse();
        assertThat(FiltroDePortas.hostLocal(null, 8095)).isFalse();
    }
}
