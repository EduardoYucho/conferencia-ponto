package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConferenciaDoTextoTest {

    private static final UUID PRINT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OUTRO_PRINT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private final AtomicInteger sequencia = new AtomicInteger();
    private final ConferenciaDoTexto conferencia = new ConferenciaDoTexto(Set.of("M1", "M2", "M3", "A1", "P1", "L1"),
            List.of(new ConferenciaDoTexto.ImagemDisponivel("A1", PRINT, "a/arquivos/001_print.jpeg", "tela Pedidos (VEN010)"),
                    new ConferenciaDoTexto.ImagemDisponivel("P1", OUTRO_PRINT, "a/arquivos/print_01.png", "tela Notas")),
            new Mascaramento()::mascararTrechos,
            () -> UUID.fromString("00000000-0000-0000-0000-00000000000" + sequencia.incrementAndGet()));

    private static ConferenciaDoTexto.BlocoBruto bloco(String tipo, String texto, List<String> fontes,
                                                       ConferenciaDoTexto.ImagemBruta... imagens) {
        return new ConferenciaDoTexto.BlocoBruto(tipo, texto, fontes, List.of(imagens));
    }

    private static ConferenciaDoTexto.ImagemBruta imagem(String id, String legenda) {
        return new ConferenciaDoTexto.ImagemBruta(id, legenda);
    }

    @Test
    void fontesQueNaoExistemSaemEImagensForaDoCatalogoSaoDescartadas() {
        var bruto = new ConferenciaDoTexto.Bruto("Erro ao gravar pedido", List.of(
                bloco("paragrafo", "O cliente relatou o erro.", List.of("M1", "M99", "[a1]"), imagem("A1", "Imagem 1: o erro")),
                bloco("passo", "Abri a tela de notas.", List.of("M2"), imagem("A7", "tela inventada"), imagem("P1", "")),
                bloco("passo", "Conferi de novo.", List.of(), imagem("A1", "de novo"))), Map.of("cliente", "Loja"), "Pedidos (VEN010)");

        ConferenciaDoTexto.Conferido conferido = conferencia.conferir(bruto, "Maria");
        TextoRedigido texto = conferido.texto();

        assertThat(texto.blocos()).hasSize(3);
        assertThat(texto.blocos().get(0).fontes()).containsExactly("M1", "A1");
        assertThat(texto.blocos().get(0).imagens()).singleElement().satisfies(i -> {
            assertThat(i.numero()).isEqualTo(1);
            assertThat(i.legenda()).as("o rótulo repetido sai").isEqualTo("o erro");
        });
        assertThat(texto.blocos().get(1).tipo()).isEqualTo(BlocoDoTexto.Tipo.PASSO);
        assertThat(texto.blocos().get(1).imagens()).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo("P1");
            assertThat(i.numero()).isEqualTo(2);
            assertThat(i.legenda()).as("sem legenda do modelo, a do catálogo").isEqualTo("tela Notas");
        });
        assertThat(texto.blocos().get(2).imagens()).as("a mesma imagem só entra uma vez").isEmpty();
        assertThat(conferido.imagens()).extracting(NovaImagem::arquivoId).containsExactly(PRINT, OUTRO_PRINT);
        assertThat(conferido.imagens()).extracting(NovaImagem::numero).containsExactly(1, 2);
        assertThat(texto.avisos()).containsExactly(
                "1 fonte citada não existe no material e foi tirada.",
                "1 imagem citada não está entre as do atendimento e foi descartada.",
                "1 imagem citada de novo ficou só na primeira vez em que aparecem.",
                "1 trecho veio sem fonte (destacados na prévia): confira antes de usar.");
        assertThat(texto.telaFuncionalidade()).isEqualTo("Pedidos (VEN010)");
        assertThat(texto.base()).containsEntry("cliente", "Loja");
    }

    @Test
    void dadosDeAcessoRemotoQueEscaparamViramOmitidoComAviso() {
        var bruto = new ConferenciaDoTexto.Bruto("Acesso remoto", List.of(
                bloco("paragrafo", "Acessei pelo AnyDesk 123 456 789 com a senha abc123.", List.of("M1"))),
                Map.of("resolucao", "Acesso pelo TeamViewer 987654321"), "não identificado");

        TextoRedigido texto = conferencia.conferir(bruto, "Maria").texto();

        assertThat(texto.blocos().get(0).texto()).doesNotContain("123 456 789").doesNotContain("abc123").contains("[omitido]");
        assertThat(texto.base().get("resolucao")).doesNotContain("987654321");
        assertThat(texto.avisos()).anyMatch(a -> a.endsWith("trocados por [omitido]."));
    }

    @Test
    void assuntoGanhaOPadraoDoDigisacSemRepetirOPrefixo() {
        assertThat(ConferenciaDoTexto.assuntoCompleto("Maria Souza", "Erro na nota")).isEqualTo("Digisac: Maria Souza : Erro na nota");
        assertThat(ConferenciaDoTexto.assuntoCompleto("Maria Souza", "Digisac: Maria Souza : Erro na nota"))
                .isEqualTo("Digisac: Maria Souza : Erro na nota");
        assertThat(ConferenciaDoTexto.assuntoCompleto(null, "  ")).isEqualTo("Digisac: contato não identificado : atendimento");

        var conferido = conferencia.conferir(new ConferenciaDoTexto.Bruto("Erro na nota", List.of(), Map.of(), ""), "Maria");
        assertThat(conferido.texto().assunto()).isEqualTo("Digisac: Maria : Erro na nota");
        assertThat(conferido.assuntoLongo()).isFalse();
        assertThat(conferido.texto().avisos()).contains("O Gemini não devolveu nenhum trecho de texto.");
    }

    @Test
    void assuntoLongoECortadoNaUltimaPalavraQuandoOMaisCurtoNaoVem() {
        String longo = "Erro ao emitir a nota fiscal de saída no pedido de venda com desconto acima do limite "
                + "permitido para o cliente e a tabela de preços promocional do mês";
        var conferido = conferencia.conferir(new ConferenciaDoTexto.Bruto(longo, List.of(), Map.of(), ""), "Maria Souza");
        assertThat(conferido.assuntoLongo()).isTrue();

        TextoRedigido cortado = ConferenciaDoTexto.comAssunto(conferido.texto(), "Maria Souza", conferido.assuntoDoModelo());
        assertThat(cortado.assunto()).startsWith("Digisac: Maria Souza : Erro ao emitir").hasSizeLessThanOrEqualTo(150)
                .doesNotEndWith(" ").doesNotEndWith(",");
        assertThat(longo).contains(cortado.assunto().substring("Digisac: Maria Souza : ".length()));
        assertThat(cortado.avisos()).anyMatch(a -> a.contains("foi cortado na última palavra"));

        TextoRedigido curto = ConferenciaDoTexto.comAssunto(conferido.texto(), "Maria Souza", "Erro na NF-e com desconto");
        assertThat(curto.assunto()).isEqualTo("Digisac: Maria Souza : Erro na NF-e com desconto");
        assertThat(curto.avisos()).noneMatch(a -> a.contains("cortado"));
    }

    @Test
    void identificadoresEnfeitadosSaoReconhecidos() {
        assertThat(ConferenciaDoTexto.normalizar("m12")).isEqualTo("M12");
        assertThat(ConferenciaDoTexto.normalizar("A03")).isEqualTo("A3");
        assertThat(ConferenciaDoTexto.normalizar("[A3]")).isEqualTo("A3");
        assertThat(ConferenciaDoTexto.normalizar("A3 (print.png)")).isEqualTo("A3");
        assertThat(ConferenciaDoTexto.normalizar("V1@02:15")).isEqualTo("V1");
        assertThat(ConferenciaDoTexto.normalizar("Imagem 1")).isNull();
        assertThat(ConferenciaDoTexto.normalizar(null)).isNull();
    }
}
