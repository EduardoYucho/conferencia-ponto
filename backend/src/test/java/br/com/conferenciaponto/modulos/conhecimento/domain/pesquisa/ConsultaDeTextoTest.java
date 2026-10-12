package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultaDeTextoTest {

    @Test
    void palavrasSemAcentoEmMinusculasSemSinais() {
        ConsultaDeTexto consulta = ConsultaDeTexto.de("  Não foi possível GRAVAR o pedido: \"estoque\" & | ! ");

        assertThat(consulta.palavras()).containsExactly("nao", "foi", "possivel", "gravar", "pedido", "estoque");
        assertThat(consulta.todas()).isEqualTo("nao & foi & possivel & gravar & pedido & estoque");
        assertThat(consulta.termo()).isEqualTo("Não foi possível GRAVAR o pedido: \"estoque\" & | !");
        assertThat(consulta.vazia()).isFalse();
    }

    @Test
    void palavrasEmAoLevamOSingularEOPlural() {
        ConsultaDeTexto consulta = ConsultaDeTexto.de("configurações da emissão");

        assertThat(consulta.todas()).isEqualTo("(configuracoes | configuracao) & da & (emissao | emissoes | emissaes | emissaos)");
        assertThat(consulta.qualquer()).isEqualTo("configuracoes | configuracao | da | emissao | emissoes | emissaes | emissaos");
    }

    @Test
    void soSinaisOuVazioNaoProcuraNada() {
        assertThat(ConsultaDeTexto.de("  ").vazia()).isTrue();
        assertThat(ConsultaDeTexto.de(null).vazia()).isTrue();
        assertThat(ConsultaDeTexto.de("&|!():*'").vazia()).isTrue();
        assertThat(ConsultaDeTexto.de("a e").vazia()).isTrue();
    }

    @Test
    void pedacosDeCodigoTemPeloMenosTresCaracteres() {
        assertThat(ConsultaDeTexto.de("eqr7 ab ven010").pedacosDeCodigo()).containsExactly("eqr7", "ven010");
    }

    @Test
    void limitaAsPalavrasEOTamanho() {
        String muitas = "um dois tres quatro cinco seis sete oito nove dez onze doze treze catorze";
        assertThat(ConsultaDeTexto.de(muitas).palavras()).hasSize(ConsultaDeTexto.MAXIMO_DE_PALAVRAS);
        assertThat(ConsultaDeTexto.de("x".repeat(500)).termo()).hasSize(ConsultaDeTexto.MAXIMO_DO_TERMO);
    }
}
