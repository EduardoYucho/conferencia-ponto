package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.ImagemDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlDaSaidaTest {

    private static final UUID SAIDA = UUID.randomUUID();
    private static final UUID I1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID I2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID I3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final List<ImagemDaSaida> IMAGENS = List.of(
            new ImagemDaSaida(I1, SAIDA, 1, null, "a/001.jpeg", "tela de pedidos", true),
            new ImagemDaSaida(I2, SAIDA, 2, null, "a/002.png", "mensagem de erro", true),
            new ImagemDaSaida(I3, SAIDA, 3, null, "a/003.png", "tela de notas", false));

    private static final String MONTADO = """
            <p data-fontes="M1 A1">O cliente relatou o erro.</p>
            <p data-imagem="11111111-1111-1111-1111-111111111111"><strong>Imagem 1</strong>: tela de pedidos</p>
            <ol>
            <li data-fontes="M4">Alterei o desconto.<p data-imagem="22222222-2222-2222-2222-222222222222"><strong>Imagem 2</strong>: mensagem de erro</p></li>
            </ol>""";

    @Test
    void aLimpezaTiraScriptEstiloEventosLinksEImagensColadas() {
        String sujo = """
                <p data-fontes="M1 X9 A1" style="color:red" onclick="roubar()">Texto <script>alert(1)</script><b>forte</b>\
                <a href="javascript:x">link</a><img src="x" onerror="y"></p><style>p{}</style>\
                <div class="x">bloco</div><p data-imagem="nao-e-uuid">Imagem</p><iframe src="https://x"></iframe>""";

        String limpo = HtmlDaSaida.limpar(sujo);

        assertThat(limpo).doesNotContain("script").doesNotContain("style").doesNotContain("onclick").doesNotContain("<a ")
                .doesNotContain("<img").doesNotContain("iframe").doesNotContain("onerror").doesNotContain("nao-e-uuid")
                .doesNotContain("class=");
        assertThat(limpo).contains("<p data-fontes=\"M1 A1\">Texto <b>forte</b>link</p>").contains("bloco");
        assertThat(HtmlDaSaida.temConteudo(HtmlDaSaida.limpar("<script>x</script><p> </p>"))).isFalse();
    }

    @Test
    void renumeraNaOrdemDoTextoComALegendaQueAPessoaDeixou() {
        String editado = """
                <p data-imagem="22222222-2222-2222-2222-222222222222"><strong>Imagem 2</strong>: o erro na gravação</p>
                <p>texto novo</p>
                <p data-imagem="11111111-1111-1111-1111-111111111111">Imagem 1</p>
                <p data-imagem="22222222-2222-2222-2222-222222222222">Imagem 2: repetida</p>
                <p data-imagem="44444444-4444-4444-4444-444444444444">Imagem de fora</p>""";

        HtmlDaSaida.Renumerado r = HtmlDaSaida.renumerar(editado, IMAGENS);

        assertThat(r.html()).contains("<p data-imagem=\"22222222-2222-2222-2222-222222222222\"><strong>Imagem 1</strong>: o erro na gravação</p>")
                .contains("<p data-imagem=\"11111111-1111-1111-1111-111111111111\"><strong>Imagem 2</strong></p>")
                .doesNotContain("repetida").doesNotContain("Imagem de fora");
        assertThat(r.ordem()).containsExactly(
                new Saidas.ImagemNumerada(I2, 1, true, "o erro na gravação"),
                new Saidas.ImagemNumerada(I1, 2, true, null),
                new Saidas.ImagemNumerada(I3, 3, false, "tela de notas"));
    }

    @Test
    void permutarTrocaAsImagensNosLugaresTiraASobraEPoeANovaNoFim() {
        String trocadas = HtmlDaSaida.permutar(MONTADO, List.of(I2, I1), IMAGENS);
        HtmlDaSaida.Renumerado r = HtmlDaSaida.renumerar(trocadas, IMAGENS);
        assertThat(r.html()).contains("<p data-imagem=\"22222222-2222-2222-2222-222222222222\"><strong>Imagem 1</strong>: mensagem de erro</p>\n<ol>")
                .contains("Alterei o desconto.<p data-imagem=\"11111111-1111-1111-1111-111111111111\"><strong>Imagem 2</strong>: tela de pedidos</p></li>");

        String umaSo = HtmlDaSaida.renumerar(HtmlDaSaida.permutar(MONTADO, List.of(I2), IMAGENS), IMAGENS).html();
        assertThat(umaSo).contains("<strong>Imagem 1</strong>: mensagem de erro").doesNotContain("Imagem 2")
                .contains("<li data-fontes=\"M4\">Alterei o desconto.</li>");

        String comAVolta = HtmlDaSaida.permutar(MONTADO, List.of(I1, I2, I3), IMAGENS);
        assertThat(comAVolta).endsWith("<p data-imagem=\"33333333-3333-3333-3333-333333333333\"><strong>Imagem 3</strong>: tela de notas</p>");
    }

    @Test
    void copiaSemOsMarcadoresDaTelaComAsImagensEmbutidasDepoisDoRotulo() {
        String soTexto = HtmlDaSaida.paraCopiar(MONTADO, null);
        assertThat(soTexto).doesNotContain("data-").contains("<p><strong>Imagem 1</strong>: tela de pedidos</p>")
                .doesNotContain("<img");

        String comImagens = HtmlDaSaida.paraCopiar(MONTADO, id -> id.equals(I1) ? Optional.of("data:image/jpeg;base64,AAAA")
                : Optional.empty());
        assertThat(comImagens).doesNotContain("data-fontes").doesNotContain("data-imagem")
                .contains("<p><strong>Imagem 1</strong>: tela de pedidos</p><p><img src=\"data:image/jpeg;base64,AAAA\" alt=\"Imagem 1\"></p>")
                .containsOnlyOnce("<img");
    }

    @Test
    void textoSimplesComParagrafosEPassosNumerados() {
        assertThat(HtmlDaSaida.textoSimples(MONTADO + "<p>Linha 1<br>Linha 2</p>")).isEqualTo("""
                O cliente relatou o erro.

                Imagem 1: tela de pedidos

                1. Alterei o desconto.
                   Imagem 2: mensagem de erro

                Linha 1
                Linha 2""");
    }

    @Test
    void legendaDepoisDoRotulo() {
        assertThat(HtmlDaSaida.legendaDe("Imagem 12: tela X")).isEqualTo("tela X");
        assertThat(HtmlDaSaida.legendaDe("imagem 3 - tela Y")).isEqualTo("tela Y");
        assertThat(HtmlDaSaida.legendaDe("Imagem 4")).isNull();
        assertThat(HtmlDaSaida.legendaDe("só a legenda")).isEqualTo("só a legenda");
    }
}
