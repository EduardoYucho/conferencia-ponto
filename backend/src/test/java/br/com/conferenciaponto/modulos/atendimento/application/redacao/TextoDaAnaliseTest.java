package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O conteúdo de cada análise como entra no material da redação. */
class TextoDaAnaliseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void videoDePedidoLevaOPedidoOsItensOModeloEOQueApareceNaTela() throws Exception {
        String texto = TextoDaAnalise.texto("video", json.readTree(ModeloDeMentira.VIDEO_DE_PEDIDO));

        assertThat(texto).contains("O vídeo explica um pedido do cliente.")
                .contains("Pedido: Criar um relatório de vendas por vendedor igual à planilha da diretoria")
                .contains("Itens do pedido:\n- coluna Vendedor\n- coluna Total vendido no mês\n- filtro por período")
                .contains("Modelos mostrados:\n- planilha com as colunas Vendedor, Meta, Total vendido")
                .contains("Passo 1 [00:05]: Mostra o relatório atual. (tela Vendas por Período (VEN050), menu Relatórios > Vendas)")
                .contains("\n  Na tela: colunas: Data, Cliente, Valor")
                .contains("Narração:\n[00:01] Falante 1: a diretoria quer ver por vendedor, igual a planilha");
    }

    @Test
    void videoDeProblemaSemPedidoNaoInventaUmPedido() throws Exception {
        String texto = TextoDaAnalise.texto("video", json.readTree(ModeloDeMentira.VIDEO));

        assertThat(texto).contains("O vídeo reproduz um problema.").doesNotContain("Pedido:").doesNotContain("Itens do pedido")
                .contains("Mensagem na tela (erro): \"Erro ao gravar o pedido\"").contains("Na tela: Desconto: 10");
    }

    @Test
    void videoDaVersaoAnteriorContinuaLegivel() throws Exception {
        String antigo = """
                {"descricao":"Gravação","duracao":"00:10","passos":[{"instante":"00:01","tela":{"nome":"Pedidos","codigo":"",
                 "caminhoMenu":""},"acoes":["clique em Gravar"],"mensagens":[],"descricao":"Grava.","instanteDoQuadro":"00:02"}],
                 "narracao":[],"textoLegivel":true}""";

        String texto = TextoDaAnalise.texto("video", json.readTree(antigo));

        assertThat(texto).startsWith("Vídeo (duração 00:10): Gravação").contains("Passo 1 [00:01]: Grava.")
                .doesNotContain("Na tela").doesNotContain("pedido");
    }
}
