package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** O texto que o Gem escreveu, colado de volta, lido em trechos, partes, imagens e dados para a base. */
class TextoColadoDoGeminiTest {

    private static List<String> textos(ConferenciaDoTexto.Bruto bruto) {
        return bruto.blocos().stream().map(ConferenciaDoTexto.BlocoBruto::texto).toList();
    }

    @Test
    void resumoComAssuntoParagrafosPassosEImagens() {
        ConferenciaDoTexto.Bruto bruto = TextoColadoDoGemini.ler("""
                **Assunto:** Digisac: Ana - Transportadora Exemplo : Erro ao gerar o SINTEGRA

                A cliente Ana relatou que o **SINTEGRA** não era gerado:
                aparecia a mensagem "Arquivo inválido".
                [Imagem A1: a mensagem de erro]

                O que foi feito:

                1. Acessei o computador da cliente por acesso remoto.
                2. Conferi o cadastro da empresa (M12, A3) e corrigi a inscrição estadual de 123 para 456.
                [Imagem A4@01:15 – a tela do cadastro]
                3) Gerei o arquivo de novo.

                [Imagem A8: o arquivo gerado]

                O arquivo foi gerado e enviado.
                """, ModeloDoTexto.RESUMO);

        assertThat(bruto.assunto()).isEqualTo("Digisac: Ana - Transportadora Exemplo : Erro ao gerar o SINTEGRA");
        assertThat(textos(bruto)).containsExactly(
                "A cliente Ana relatou que o SINTEGRA não era gerado: aparecia a mensagem \"Arquivo inválido\".",
                "O que foi feito:",
                "Acessei o computador da cliente por acesso remoto.",
                "Conferi o cadastro da empresa e corrigi a inscrição estadual de 123 para 456.",
                "Gerei o arquivo de novo.",
                "O arquivo foi gerado e enviado.");
        assertThat(bruto.blocos()).extracting(ConferenciaDoTexto.BlocoBruto::tipo)
                .containsExactly("paragrafo", "paragrafo", "passo", "passo", "passo", "paragrafo");
        assertThat(bruto.blocos().get(0).imagens()).containsExactly(new ConferenciaDoTexto.ImagemBruta("A1", "a mensagem de erro"));
        assertThat(bruto.blocos().get(3).imagens()).containsExactly(new ConferenciaDoTexto.ImagemBruta("A4@01:15", "a tela do cadastro"));
        assertThat(bruto.blocos().get(3).fontes()).as("os identificadores escapados viram as fontes").containsExactly("M12", "A3");
        assertThat(bruto.blocos().get(4).imagens()).as("imagem depois de uma linha em branco fica no trecho de antes")
                .containsExactly(new ConferenciaDoTexto.ImagemBruta("A8", "o arquivo gerado"));
        assertThat(bruto.blocos()).allMatch(b -> b.secao() == null);
        assertThat(bruto.base()).isEmpty();
    }

    @Test
    void chamadoDeErroPelasPartesComATelaEOsDadosParaABase() {
        ConferenciaDoTexto.Bruto bruto = TextoColadoDoGemini.ler("""
                ```
                Assunto: Digisac: Loja - Maria : Erro ao emitir
                **Tela/Funcionalidade:** Pedidos de Vendas (EQR76)

                Ao emitir, o sistema trava.

                ## Passos para reproduzir
                - Abrir a tela Pedidos de Vendas (EQR76).
                - Clicar em Emitir.

                **Resultado obtido:**
                A mensagem "Erro 42".
                [Imagem A2: o erro]

                ### Causa
                Causa não identificada.

                ---
                **Dados para a base de conhecimento**
                - Cliente: Loja Exemplo
                - Código da tela: EQR76
                ```
                """, ModeloDoTexto.DEV_ERRO);

        assertThat(bruto.telaFuncionalidade()).isEqualTo("Pedidos de Vendas (EQR76)");
        assertThat(bruto.blocos()).extracting(ConferenciaDoTexto.BlocoBruto::secao)
                .containsExactly("relato", "passos", "passos", "resultadoObtido", "causa");
        assertThat(bruto.blocos()).filteredOn(b -> "passos".equals(b.secao())).allMatch(b -> b.tipo().equals("passo"));
        assertThat(textos(bruto)).containsExactly("Ao emitir, o sistema trava.", "Abrir a tela Pedidos de Vendas (EQR76).",
                "Clicar em Emitir.", "A mensagem \"Erro 42\".", "Causa não identificada.");
        assertThat(bruto.blocos().get(3).imagens()).extracting(ConferenciaDoTexto.ImagemBruta::id).containsExactly("A2");
        assertThat(bruto.base()).containsEntry("cliente", "Loja Exemplo").containsEntry("codigoTela", "EQR76");
    }

    @Test
    void chamadoDeAlteracaoSemAAberturaEComOsDadosDaBaseNoFim() {
        ConferenciaDoTexto.Bruto bruto = TextoColadoDoGemini.ler("""
                Assunto: Digisac: Bruna - Loja Exemplo : Novo relatório de vendas por vendedor
                Tela/Funcionalidade: Relatórios de Vendas

                Solicitação de Melhoria

                ## Descrição resumida
                Criar o relatório Vendas por Vendedor.

                ## Detalhamento do que criar ou mudar
                1. Coluna Vendedor.
                2. Coluna Valor Total.

                ## Pontos a confirmar com o cliente
                1. Qual período o relatório deve aceitar?

                Cliente: Loja da Bruna
                Módulo: Vendas
                Tela: Relatórios de Vendas
                Sintoma: precisa ver as vendas por vendedor
                """, ModeloDoTexto.DEV_ALTERACAO);

        assertThat(textos(bruto)).containsExactly("Criar o relatório Vendas por Vendedor.", "Coluna Vendedor.",
                "Coluna Valor Total.", "Qual período o relatório deve aceitar?");
        assertThat(bruto.blocos()).extracting(ConferenciaDoTexto.BlocoBruto::secao)
                .containsExactly("descricao", "detalhamento", "detalhamento", "pontosAConfirmar");
        assertThat(bruto.base()).containsEntry("cliente", "Loja da Bruna").containsEntry("modulo", "Vendas")
                .containsEntry("tela", "Relatórios de Vendas").containsEntry("sintoma", "precisa ver as vendas por vendedor");
    }

    @Test
    void textoSemAssuntoNemPartesEntraInteiroNaParteDoRelato() {
        ConferenciaDoTexto.Bruto bruto = TextoColadoDoGemini.ler("O cliente relatou um erro.\nTela: Pedidos\n\nNada mais.",
                ModeloDoTexto.DEV_ERRO);

        assertThat(bruto.assunto()).isEmpty();
        assertThat(textos(bruto)).as("uma linha \"Tela:\" sozinha é texto, não dado para a base")
                .containsExactly("O cliente relatou um erro. Tela: Pedidos", "Nada mais.");
        assertThat(bruto.blocos()).allMatch(b -> "relato".equals(b.secao()));
        assertThat(bruto.base()).isEmpty();
    }

    @Test
    void imagemAntesDeQualquerTextoFicaNoPrimeiroTrecho() {
        ConferenciaDoTexto.Bruto bruto = TextoColadoDoGemini.ler("[Imagem P1: a tela]\n\nO relatório saiu em branco.",
                ModeloDoTexto.RESUMO);

        assertThat(bruto.blocos()).singleElement().satisfies(b -> {
            assertThat(b.texto()).isEqualTo("O relatório saiu em branco.");
            assertThat(b.imagens()).extracting(ConferenciaDoTexto.ImagemBruta::id).containsExactly("P1");
        });
    }

    @Test
    void nasPartesDePassosCadaLinhaEUmPassoSemNumero() {
        ConferenciaDoTexto.Bruto bruto = TextoColadoDoGemini.ler("""
                ## Passos para reproduzir
                Abrir a tela Pedidos de Vendas (EQR76).
                Clicar em Emitir.
                [Imagem A2: o erro]

                ## Relato do problema
                Ao emitir, o sistema trava
                e mostra uma mensagem.
                """, ModeloDoTexto.DEV_ERRO);

        assertThat(textos(bruto)).containsExactly("Ao emitir, o sistema trava e mostra uma mensagem.",
                "Abrir a tela Pedidos de Vendas (EQR76).", "Clicar em Emitir.");
        assertThat(bruto.blocos().get(2).imagens()).extracting(ConferenciaDoTexto.ImagemBruta::id).containsExactly("A2");
        assertThat(bruto.blocos()).filteredOn(b -> "passos".equals(b.secao())).allMatch(b -> b.tipo().equals("passo"));
    }
}
