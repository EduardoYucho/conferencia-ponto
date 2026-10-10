package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento.OMITIDO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chave do bot e dados de acesso remoto viram [omitido]. Os casos são sintéticos, no formato dos PDFs reais (cujos
 * valores nunca foram exibidos nem copiados).
 */
class MascaramentoTest {

    private final Mascaramento mascaramento = new Mascaramento();

    /** Uma conversa com um evento e as mensagens dadas: cada par é (lado, texto). */
    private static ConversaLida conversa(Object... ladoETexto) {
        return conversaComAssunto(null, ladoETexto);
    }

    private static ConversaLida conversaComAssunto(String assunto, Object... ladoETexto) {
        List<ItemDaConversa> itens = new ArrayList<>();
        itens.add(new ItemDaConversa(1, TipoDeItem.EVENTO, null, null, null, "Início do chamado - ID 99887766", List.of(), 1));
        for (int i = 0; i < ladoETexto.length; i += 2) {
            itens.add(new ItemDaConversa(itens.size() + 1, TipoDeItem.MENSAGEM, (Lado) ladoETexto[i], "Pessoa",
                    Instant.parse("2026-10-09T11:00:00Z"), (String) ladoETexto[i + 1], List.of(), 1));
        }
        Cabecalho cabecalho = new Cabecalho("20261009000001", "Loja Exemplo", null, null, assunto, null);
        return new ConversaLida(cabecalho, itens, List.of(), 0, 0);
    }

    private static List<String> textos(Mascaramento.Resultado resultado) {
        return resultado.conversa().itens().stream().filter(ItemDaConversa::mensagem).map(ItemDaConversa::texto).toList();
    }

    @Test
    void chaveTemporariaDoBotNasDuasFormasDoAviso() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(
                Lado.ATENDENTE, "*Atenção! Informamos que nossa chave temporária para o mês de\nOutubro/2026 é: 4815162342 - "
                        + "Válida até o dia 31/10/2026*",
                Lado.ATENDENTE, "A chave temporária deste mês é: X7K2P9Q4 (não compartilhe)"));

        assertThat(textos(r)).containsExactly(
                "*Atenção! Informamos que nossa chave temporária para o mês de\nOutubro/2026 é: " + OMITIDO
                        + " - Válida até o dia 31/10/2026*",
                "A chave temporária deste mês é: " + OMITIDO + " (não compartilhe)");
        assertThat(r.omitidos().chaveDoBot()).isEqualTo(2);
        assertThat(r.omitidos().acessoRemoto()).isZero();
        assertThat(r.valores()).containsExactlyInAnyOrder("4815162342", "X7K2P9Q4");
    }

    @Test
    void idEsenhaDeAcessoRemotoNaMesmaMensagem() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(
                Lado.CLIENTE, "ID 123 456 789 senha 4821",
                Lado.CLIENTE, "anydesk: 987654321",
                Lado.CLIENTE, "o ultra é 12 345 678, a senha é ab12cd",
                Lado.CLIENTE, "TeamViewer 1 234 567 890 / Senha: x9y8z7"));

        assertThat(textos(r)).containsExactly(
                "ID " + OMITIDO + " senha " + OMITIDO,
                "anydesk: " + OMITIDO,
                "o ultra é " + OMITIDO + ", a senha é " + OMITIDO,
                "TeamViewer " + OMITIDO + " / Senha: " + OMITIDO);
        assertThat(r.omitidos().acessoRemoto()).isEqualTo(7);
    }

    @Test
    void idSoltoESenhaNaMensagemSeguinte() {
        // como nos PDFs reais: o cliente manda só o número do UltraViewer e, em seguida, a senha
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(
                Lado.ATENDENTE, "Pode me passar o ID e a senha do Ultra?",
                Lado.CLIENTE, "12 345 678",
                Lado.CLIENTE, "4821"));

        assertThat(textos(r)).containsExactly("Pode me passar o ID e a senha do Ultra?", OMITIDO, OMITIDO);
        assertThat(r.omitidos().acessoRemoto()).isEqualTo(2);
    }

    @Test
    void senhaNaMensagemSeguinteQuandoAAnteriorTerminaEmSenha() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(
                Lado.CLIENTE, "segue a senha:",
                Lado.CLIENTE, "Ab3@x9"));

        assertThat(textos(r)).containsExactly("segue a senha:", OMITIDO);
    }

    @Test
    void aSenhaSeguinteSoValeDoMesmoLado() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(
                Lado.CLIENTE, "98765432",
                Lado.ATENDENTE, "ok2",
                Lado.CLIENTE, "4821"));

        assertThat(textos(r)).containsExactly(OMITIDO, "ok2", OMITIDO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bom dia, a senha do sistema não entra",
            "esqueci a senha",
            "a nota 1234 deu erro",
            "o pedido tem 3 itens e custa 1.250,00",
            "chamado 20261009000001 aberto",
            "1",
            "o código da tela é 102030"})
    void textoComumNaoEMascarado(String texto) {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(Lado.CLIENTE, texto));

        assertThat(textos(r)).containsExactly(texto);
        assertThat(r.omitidos().total()).isZero();
    }

    @Test
    void numeroSozinhoForaDaFaixaDeIdNaoEMascarado() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(
                Lado.CLIENTE, "1234567",
                Lado.CLIENTE, "12345678901"));

        assertThat(textos(r)).containsExactly("1234567", "12345678901");
    }

    @Test
    void assuntoEResumoDoCabecalhoTambemPassamPelasRegras() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversaComAssunto("Acesso pelo ID 123 456 789", Lado.CLIENTE, "oi"));

        assertThat(r.conversa().cabecalho().assunto()).isEqualTo("Acesso pelo ID " + OMITIDO);
        assertThat(r.omitidos().acessoRemoto()).isEqualTo(1);
    }

    @Test
    void eventosDoSistemaFicamComoEstao() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(Lado.CLIENTE, "oi"));

        assertThat(r.conversa().itens().get(0).texto()).isEqualTo("Início do chamado - ID 99887766");
    }

    @Test
    void osValoresTrocadosNaoAparecemNoToString() {
        Mascaramento.Resultado r = mascaramento.aplicar(conversa(Lado.CLIENTE, "ID 123 456 789 senha 4821"));

        assertThat(r.toString()).doesNotContain("4821").doesNotContain("123 456 789");
        assertThat(r.conversa().itens().get(1).toString()).doesNotContain("4821");
    }

    @Test
    void textoSoltoSemContexto() {
        assertThat(mascaramento.mascarar("a senha é 4321")).isEqualTo("a senha é " + OMITIDO);
        assertThat(mascaramento.mascarar(null)).isNull();
        assertThat(mascaramento.mascarar("")).isEmpty();
    }
}
