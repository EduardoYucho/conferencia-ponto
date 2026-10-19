package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.CamposInformados;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** As partes dos chamados para o desenvolvimento, o HTML de cada tipo e o cabeçalho e o rodapé. */
class ModeloDoTextoTest {

    private static final UUID IMAGEM = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static ConferenciaDoTexto.BlocoBruto bruto(String tipo, String texto, String... fontes) {
        return new ConferenciaDoTexto.BlocoBruto(tipo, texto, List.of(fontes), List.of());
    }

    @Test
    void partesNaOrdemDoModeloPassosNumeradosEParteDesconhecidaFora() {
        Map<String, List<ConferenciaDoTexto.BlocoBruto>> secoes = new LinkedHashMap<>();
        secoes.put("pedido", List.of(bruto("paragrafo", "Corrigir.", "M1")));
        secoes.put("inventada", List.of(bruto("paragrafo", "nada", "M1")));
        secoes.put("passos", List.of(bruto("paragrafo", "Abrir a tela.", "A1"), bruto(null, "Gravar.", "A1")));
        secoes.put("relato", List.of(bruto("passo", "O erro aparece.", "M1")));

        List<ConferenciaDoTexto.BlocoBruto> ordenados = ModeloDoTexto.DEV_ERRO.ordenar(secoes);

        assertThat(ordenados).extracting(ConferenciaDoTexto.BlocoBruto::secao).containsExactly("relato", "passos", "passos", "pedido");
        assertThat(ordenados).extracting(ConferenciaDoTexto.BlocoBruto::tipo).containsExactly("passo", "passo", "passo", "paragrafo");
    }

    @Test
    void causaVaziaViraCausaNaoIdentificadaNoLugarDela() {
        List<BlocoDoTexto> blocos = List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "O erro aparece.", List.of("M1"), List.of(), "relato", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Corrigir.", List.of("M1"), List.of(), "pedido", false));

        List<BlocoDoTexto> completos = ModeloDoTexto.DEV_ERRO.completar(blocos);

        assertThat(completos).extracting(BlocoDoTexto::secao).containsExactly("relato", "causa", "pedido");
        assertThat(completos.get(1).texto()).isEqualTo("Causa não identificada.");
        assertThat(completos.get(1).doSistema()).isTrue();
        assertThat(ModeloDoTexto.RESUMO.completar(blocos)).isSameAs(blocos);
        assertThat(ModeloDoTexto.DEV_ALTERACAO.completar(List.of())).as("na alteração nada é obrigatório").isEmpty();
    }

    @Test
    void htmlDoChamadoDeErroComOsTitulosDasPartes() {
        List<BlocoDoTexto> blocos = ModeloDoTexto.DEV_ERRO.completar(List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Vendas -> Pedidos (VEN010)", List.of("A1"), List.of(), "caminhoTela", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "Abrir a tela Pedidos.", List.of("A1"), List.of(), "passos", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "Clicar em Gravar.", List.of("A1"),
                        List.of(new BlocoDoTexto.ImagemCitada("A1", IMAGEM, 1, "o erro")), "passos", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Aparece \"Erro ao gravar\".", List.of("A1"), List.of(), "resultadoObtido", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Verificar e corrigir.", List.of("M1"), List.of(), "pedido", false)));

        assertThat(MontagemDoHtml.montar(blocos, ModeloDoTexto.DEV_ERRO)).isEqualTo("""
                <p><strong>Caminho da tela</strong></p>
                <p data-fontes="A1">Vendas -&gt; Pedidos (VEN010)</p>
                <p><strong>Passos para reproduzir</strong></p>
                <p data-fontes="A1">Abrir a tela Pedidos.</p>
                <p data-fontes="A1">Clicar em Gravar.</p>
                <p data-imagem="11111111-1111-1111-1111-111111111111"><strong>Imagem 1</strong>: o erro</p>
                <p><strong>Resultado obtido</strong></p>
                <p data-fontes="A1">Aparece &quot;Erro ao gravar&quot;.</p>
                <p><strong>Causa</strong></p>
                <p>Causa não identificada.</p>
                <p><strong>Pedido ao desenvolvimento</strong></p>
                <p data-fontes="M1">Verificar e corrigir.</p>""");
    }

    @Test
    void htmlDoChamadoDeAlteracaoComAAbertura() {
        List<BlocoDoTexto> blocos = List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Vendas -> Pedidos (VEN010)", List.of("A1"), List.of(), "modulo", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Hoje aceita qualquer desconto.", List.of("M1"), List.of(), "comportamentoAtual", false),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Pedir a senha do gerente.", List.of("M1"), List.of(), "comportamentoEsperado", false));

        assertThat(MontagemDoHtml.montar(blocos, ModeloDoTexto.DEV_ALTERACAO)).isEqualTo("""
                <p><strong>Solicitação de Melhoria</strong></p>
                <p><strong>Módulo e telas envolvidas</strong></p>
                <p data-fontes="A1">Vendas -&gt; Pedidos (VEN010)</p>
                <p><strong>Comportamento atual</strong></p>
                <p data-fontes="M1">Hoje aceita qualquer desconto.</p>
                <p><strong>Comportamento esperado</strong></p>
                <p data-fontes="M1">Pedir a senha do gerente.</p>""");
    }

    @Test
    void cabecalhoERodapeComOsCamposECampoVazioComoNaoInformado() {
        CamposInformados vazio = new CamposInformados("  ", null, null, "");
        assertThat(CabecalhoDoChamado.cabecalho(TipoDeSaida.DEV_ERRO, vazio, "não identificado")).isEqualTo("""
                <p><strong>Tela/Funcionalidade:</strong> não informado</p>
                <p><strong>Versão do cliente:</strong> não informado</p>
                <p><strong>Testado na última versão disponível:</strong> não informado</p>
                """);
        assertThat(CabecalhoDoChamado.rodape(TipoDeSaida.DEV_ERRO, vazio)).isEqualTo("""
                <p><strong>Data de atualização do sistema:</strong> não informado</p>
                <p><strong>Caminho do backup:</strong> não informado</p>
                """);

        CamposInformados campos = new CamposInformados("01/10/2026", false, "Z:\\Bases Clientes\\Loja <X>\\", "OTRS 123");
        assertThat(CabecalhoDoChamado.cabecalho(TipoDeSaida.DEV_ALTERACAO, campos, "Pedidos (VEN010)")).isEqualTo("""
                <p><strong>Tela/Funcionalidade:</strong> Pedidos (VEN010)</p>
                <p><strong>Versão do cliente:</strong> 01/10/2026</p>
                <p><strong>Testado na última versão disponível:</strong> Não</p>
                <p><strong>Chamado relacionado:</strong> OTRS 123</p>
                """);
        assertThat(CabecalhoDoChamado.rodape(TipoDeSaida.DEV_ALTERACAO, campos)).as("a alteração só leva o backup")
                .isEqualTo("<p><strong>Caminho do backup:</strong> Z:\\Bases Clientes\\Loja &lt;X&gt;\\</p>\n");
        assertThat(CabecalhoDoChamado.cabecalho(TipoDeSaida.RESUMO, campos, "x")).isEmpty();
        assertThat(CabecalhoDoChamado.rodape(TipoDeSaida.RESUMO, campos)).isEmpty();
        assertThat(new CamposInformados(" a\nb ", true, null, null).versaoCliente()).isEqualTo("a b");
    }
}
