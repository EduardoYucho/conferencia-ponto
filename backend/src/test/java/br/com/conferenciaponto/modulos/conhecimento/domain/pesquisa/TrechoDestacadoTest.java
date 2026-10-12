package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrechoDestacadoTest {

    private static final String I = String.valueOf(TrechoDestacado.INICIO);
    private static final String F = String.valueOf(TrechoDestacado.FIM);
    private static final String E = String.valueOf(TrechoDestacado.ENTRE_FRAGMENTOS);

    @Test
    void asMarcasVoltamParaOTextoComAcento() {
        String original = "Configuração de impressão não abre";
        String semAcento = "Configuracao de impressao nao abre";
        String destacado = I + "Configuracao" + F + " de " + I + "impressao" + F + " nao abre";

        Trecho trecho = TrechoDestacado.de(original, semAcento, destacado);

        assertThat(trecho.partes()).containsExactly(new Trecho.Parte("Configuração", true), new Trecho.Parte(" de ", false),
                new Trecho.Parte("impressão", true), new Trecho.Parte(" não abre", false));
        assertThat(trecho.texto()).isEqualTo(original);
    }

    @Test
    void fragmentosComReticencias() {
        String original = "Ao gravar o pedido aparece um aviso. Depois de muito texto, o estoque ficou negativo no depósito.";
        String semAcento = "Ao gravar o pedido aparece um aviso. Depois de muito texto, o estoque ficou negativo no deposito.";
        String destacado = "gravar o " + I + "pedido" + F + " aparece" + E + "o " + I + "estoque" + F + " ficou negativo";

        Trecho trecho = TrechoDestacado.de(original, semAcento, destacado);

        assertThat(trecho.texto()).isEqualTo("… gravar o pedido aparece … o estoque ficou negativo …");
        assertThat(trecho.partes()).filteredOn(Trecho.Parte::destaque).extracting(Trecho.Parte::texto)
                .containsExactly("pedido", "estoque");
    }

    @Test
    void unaccentQueExpandeUmCaractere() {
        String original = "Aguarde… a emissão da NF-e";
        String semAcento = "Aguarde... a emissao da NF-e";
        String destacado = "Aguarde... a " + I + "emissao" + F + " da NF-e";

        Trecho trecho = TrechoDestacado.de(original, semAcento, destacado);

        assertThat(trecho.partes()).containsExactly(new Trecho.Parte("Aguarde… a ", false), new Trecho.Parte("emissão", true),
                new Trecho.Parte(" da NF-e", false));
    }

    @Test
    void destaqueQueNaoBateFicaSemAcentoMasComAsMarcas() {
        Trecho trecho = TrechoDestacado.de("Texto original", "Outro texto", "um " + I + "pedaco" + F + " qualquer");

        assertThat(trecho.partes()).containsExactly(new Trecho.Parte("um ", false), new Trecho.Parte("pedaco", true),
                new Trecho.Parte(" qualquer", false));
    }

    @Test
    void semDestaqueOuSemTexto() {
        assertThat(TrechoDestacado.de("Só o texto", "So o texto", null).partes())
                .containsExactly(new Trecho.Parte("Só o texto", false));
        assertThat(TrechoDestacado.de(null, null, null).partes()).isEmpty();
    }

    @Test
    void inicioCortaNumaPalavra() {
        assertThat(TrechoDestacado.inicio("curto", 50).texto()).isEqualTo("curto");
        assertThat(TrechoDestacado.inicio("uma frase um pouco mais comprida que o limite", 20).texto())
                .isEqualTo("uma frase um pouco …");
    }

    @Test
    void pedacoDoCodigoDestacado() {
        Trecho trecho = TrechoDestacado.comPedacos("EQR76", List.of("eqr7"));

        assertThat(trecho.partes()).containsExactly(new Trecho.Parte("EQR7", true), new Trecho.Parte("6", false));
        assertThat(TrechoDestacado.comPedacos("VEN010", List.of("pedido")).partes())
                .containsExactly(new Trecho.Parte("VEN010", false));
    }
}
