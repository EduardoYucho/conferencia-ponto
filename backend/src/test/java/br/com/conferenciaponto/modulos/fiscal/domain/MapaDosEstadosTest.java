package br.com.conferenciaponto.modulos.fiscal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapaDosEstadosTest {

    private final MapaDosEstados mapa = new MapaDosEstados(
            Map.of(Documento.NFE, Map.of("SP", "SP", "SVRS", "SC, ES;rj", "svan", "MA")),
            Map.of("SVC-AN", "SP, SC, ES, RJ", "SVC-RS", "MA"));

    @Test
    @DisplayName("Quem autoriza cada estado, os estados de cada autorizador e a contingência")
    void consulta() {
        assertThat(mapa.autorizador(Documento.NFE, "SC")).contains("SVRS");
        assertThat(mapa.autorizador(Documento.NFE, "RJ")).contains("SVRS");
        assertThat(mapa.autorizador(Documento.NFE, "MA")).contains("SVAN");
        assertThat(mapa.autorizador(Documento.CTE, "SC")).isEmpty();
        assertThat(mapa.estados(Documento.NFE, "SVRS")).containsExactly("ES", "RJ", "SC");
        assertThat(mapa.contingencia("MA")).contains("SVC-RS");
        assertThat(mapa.estadosDaContingencia("SVC-AN")).containsExactly("ES", "RJ", "SC", "SP");
        assertThat(mapa.autorizadores().get(Documento.NFE)).containsExactlyInAnyOrder("SP", "SVRS", "SVAN");
    }

    @Test
    @DisplayName("Um estado em dois autorizadores do mesmo documento, ou um estado que não existe, é erro de configuração")
    void erros() {
        assertThatThrownBy(() -> new MapaDosEstados(Map.of(Documento.NFE, Map.of("SP", "SP", "SVRS", "SP")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NF-e de SP");
        assertThatThrownBy(() -> new MapaDosEstados(Map.of(Documento.NFE, Map.of("SVRS", "SC, XX")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("XX");
    }
}
