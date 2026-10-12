package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SugestaoDaTelaTest {

    private static ItemDaLinha print(String id, String resumo, String situacao) {
        return new ItemDaLinha(id, ItemDaLinha.TipoDoItem.ARQUIVO, Instant.now(), "cliente", null, "x", UUID.randomUUID(),
                "anexo_conversa", "imagem", id + ".png", null, situacao, resumo);
    }

    private static LinhaDoTempo linha(ItemDaLinha... itens) {
        return new LinhaDoTempo(1, Instant.now(), List.of(itens), List.of());
    }

    @Test
    void aTelaComCodigoQueMaisApareceNosPrints() {
        assertThat(SugestaoDaTela.de(linha(print("A1", "tela Notas", "analisado"), print("A2", "tela Pedidos (VEN010)", "analisado"),
                print("A3", "tela Notas Fiscais (NF001)", "analisado"), print("A4", "tela Pedidos (VEN010)", "analisado"),
                print("A5", "tela Estoque (EST1)", "falhou")))).contains("Pedidos (VEN010)");
    }

    @Test
    void semCodigoValeONomeESemTelaNaoHaSugestao() {
        assertThat(SugestaoDaTela.de(linha(print("A1", "tela Notas", "analisado"), print("A2", "Uma foto da impressora", "analisado"))))
                .contains("Notas");
        assertThat(SugestaoDaTela.de(linha(print("A1", "Uma foto", "analisado")))).isEmpty();
        assertThat(SugestaoDaTela.de(linha())).isEmpty();
    }
}
