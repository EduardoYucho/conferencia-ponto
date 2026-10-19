package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MontagemDoHtmlTest {

    private static final UUID IMAGEM_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IMAGEM_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void textoCorridoSemNumeracaoEImagemLogoDepoisDoTrechoQueIlustra() {
        List<BlocoDoTexto> blocos = List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "O cliente relatou o erro \"Falha <ao> gravar\" & pediu ajuda.",
                        List.of("M1", "A1"), List.of(new BlocoDoTexto.ImagemCitada("A1", IMAGEM_1, 1, "tela Pedidos com o erro"))),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "Alterei o desconto de 10% para 5%.", List.of("M4"), List.of()),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "Gravei o pedido.\nA nota saiu.", List.of("M5", "P1"),
                        List.of(new BlocoDoTexto.ImagemCitada("P1", IMAGEM_2, 2, null))),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Resolvido.", List.of(), List.of()));

        assertThat(MontagemDoHtml.montar(blocos)).isEqualTo("""
                <p data-fontes="M1 A1">O cliente relatou o erro &quot;Falha &lt;ao&gt; gravar&quot; &amp; pediu ajuda.</p>
                <p data-imagem="11111111-1111-1111-1111-111111111111"><strong>Imagem 1</strong>: tela Pedidos com o erro</p>
                <p data-fontes="M4">Alterei o desconto de 10% para 5%.</p>
                <p data-fontes="M5 P1">Gravei o pedido.<br>A nota saiu.</p>
                <p data-imagem="22222222-2222-2222-2222-222222222222"><strong>Imagem 2</strong></p>
                <p data-fontes="">Resolvido.</p>""");
    }

    @Test
    void nadaDoQueVemDoGeminiViraMarcacao() {
        String html = MontagemDoHtml.montar(List.of(new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO,
                "<script>alert('x')</script><b onclick=\"y\">negrito</b>", List.of("M1\" onmouseover=\"z"), List.of())));

        assertThat(html).doesNotContain("<script").doesNotContain("<b ").doesNotContain("\" onmouseover")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(MontagemDoHtml.rotulo(3, "a <b>")).isEqualTo("<strong>Imagem 3</strong>: a &lt;b&gt;");
    }
}
