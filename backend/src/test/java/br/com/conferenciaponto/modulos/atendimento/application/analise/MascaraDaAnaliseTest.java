package br.com.conferenciaponto.modulos.atendimento.application.analise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O que sai da resposta do Gemini antes de gravar, e o que fica (o que importa para o suporte). */
class MascaraDaAnaliseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode mascarar(String json) throws Exception {
        return new MascaraDaAnalise().aplicar(JSON.readTree(json));
    }

    @Test
    void valorDeSenhaTokenOuIdDeAcessoRemotoSai() throws Exception {
        JsonNode r = mascarar("""
                {"campos":[{"rotulo":"Seu ID","valor":"123 456 789"},{"rotulo":"Senha","valor":"4f9x2"},
                           {"rotulo":"ID do parceiro","valor":"987654321"},{"rotulo":"Token","valor":"ab12"},
                           {"rotulo":"AnyDesk","valor":"1 234 567 890"}]}""");

        assertThat(r.findValuesAsText("valor")).containsOnly("[omitido]");
    }

    @Test
    void numeroDeNotaChaveDeAcessoDaNfeEIdDoPedidoFicam() throws Exception {
        JsonNode r = mascarar("""
                {"dados":[{"rotulo":"Nota fiscal","valor":"000123456"},
                          {"rotulo":"Chave de acesso","valor":"4226 1012 3456 7800 0123 5500 1000 0012 3410 0012 3456"},
                          {"rotulo":"ID do pedido","valor":"55821"},{"rotulo":"Senha","valor":"não identificado"}]}""");

        assertThat(r.findValuesAsText("valor")).containsExactly("000123456",
                "4226 1012 3456 7800 0123 5500 1000 0012 3410 0012 3456", "55821", "não identificado");
    }

    @Test
    void textoLivreComSenhaOuIdDeFerramentaPassaPelasRegrasDaConversa() throws Exception {
        JsonNode r = mascarar("""
                {"trechos":[{"texto":"a senha é 4821 e o anydesk 123 456 789"}],
                 "mensagens":["Erro 500 ao gravar", "ID: 123456789"],
                 "descricao":"chave temporária para o mês de Outubro/2026 é: 99887766"}""");

        assertThat(r.at("/trechos/0/texto").asText()).isEqualTo("a senha é [omitido] e o anydesk [omitido]");
        assertThat(r.at("/mensagens/0").asText()).isEqualTo("Erro 500 ao gravar");
        assertThat(r.at("/mensagens/1").asText()).isEqualTo("ID: [omitido]");
        assertThat(r.at("/descricao").asText()).doesNotContain("99887766");
    }
}
