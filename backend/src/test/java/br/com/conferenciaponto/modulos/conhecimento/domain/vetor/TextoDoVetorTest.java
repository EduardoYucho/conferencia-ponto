package br.com.conferenciaponto.modulos.conhecimento.domain.vetor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextoDoVetorTest {

    @Test
    void oRegistroComoDocumentoNoFormatoDoModelo() {
        assertThat(TextoDoVetor.documento("Nota | duplicada", "Ao emitir sai duas vezes.", "não identificado", null,
                "  Cancelar a segunda. "))
                .isEqualTo("title: Nota / duplicada | text: Ao emitir sai duas vezes.\nCancelar a segunda.");
        assertThat(TextoDoVetor.documento(null, "sintoma", null, null, null)).isEqualTo("title: none | text: sintoma");
        assertThat(TextoDoVetor.documento("Só o título", null, null, null, null)).isEqualTo("title: Só o título | text: Só o título");
    }

    @Test
    void aPesquisaComoConsulta() {
        assertThat(TextoDoVetor.consulta("  nota duplicada ao emitir ")).isEqualTo("task: search result | query: nota duplicada ao emitir");
        assertThat(TextoDoVetor.consulta("  ")).isNull();
    }

    @Test
    void oShaMudaComOTexto() {
        assertThat(TextoDoVetor.sha256("a")).hasSize(64).isEqualTo(TextoDoVetor.sha256("a")).isNotEqualTo(TextoDoVetor.sha256("b"));
    }
}
