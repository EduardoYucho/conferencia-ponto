package br.com.conferenciaponto.modulos.conhecimento.application.registro;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.FiltrosDaPesquisa;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.PesquisarNaBase;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.ResultadoDaPesquisa;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Trecho;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.armazenamento.PastaDaBaseEmDisco;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence.RepositoriosDaBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Os casos de uso da base sobre o banco e a pasta de verdade: quem pode mudar (autor, curador; quem só pesquisa,
 * não), a edição simultânea, o histórico da situação, a validação, apagar e a pesquisa com os filtros da tela.
 */
class GerenciarRegistrosTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo"));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2};

    @TempDir
    Path raiz;

    private final ParaOsTestesDaBase base = new ParaOsTestesDaBase(RELOGIO);
    private final Registros registros = RepositoriosDaBase.registros();
    private PastaDaBaseEmDisco pasta;
    private GerenciarRegistros gerenciar;
    private RegistrarTextoConfirmado registrar;
    private PesquisarNaBase pesquisar;
    private Usuario autora;
    private Usuario leitor;
    private Usuario curadora;

    @BeforeEach
    void preparar() {
        pasta = new PastaDaBaseEmDisco(raiz);
        gerenciar = new GerenciarRegistros(registros, pasta, base.gerenciarAcessos, RELOGIO);
        registrar = new RegistrarTextoConfirmado(registros, pasta, base.gerenciarAcessos, base.usuarios);
        pesquisar = new PesquisarNaBase(registros, RELOGIO);
        autora = base.pessoa("autora", true, false);
        leitor = base.pessoa("leitor", true, false);
        curadora = base.pessoa("curadora", true, true);
    }

    private TextoConfirmado.Formulario formulario(String titulo, String tipo, String situacao) {
        return new TextoConfirmado.Formulario(titulo, tipo, "Padaria São João", "Maria", "Vendas", "Pedidos", "VEN010",
                "Ao gravar o pedido aparece erro.", "Estoque insuficiente", "Estoque negativo.", "Acertar o estoque.",
                situacao, null);
    }

    private UUID confirmar(Usuario quem, TextoConfirmado.Formulario formulario, Path... imagens) {
        UUID saida = UUID.randomUUID();
        List<TextoConfirmado.Imagem> lista = new java.util.ArrayList<>();
        for (int i = 0; i < imagens.length; i++) {
            lista.add(new TextoConfirmado.Imagem(i + 1, imagens[i], "imagem " + (i + 1)));
        }
        registrar.aoConfirmar(new TextoConfirmado(UUID.randomUUID(), saida, "dev_erro", quem.id(), quem.login(), formulario,
                "<p>Texto do chamado</p><script>x()</script><p>Imagem 1: tela</p>", lista, AGORA));
        return gerenciar.daSaida(saida);
    }

    @Test
    void oTextoConfirmadoViraRegistroComAsImagensCopiadas() throws Exception {
        Path imagem = Files.write(raiz.resolve("print.png"), PNG);

        UUID id = confirmar(autora, formulario("Erro ao gravar o pedido", "erro", "enviado_desenvolvimento"), imagem);
        RegistroView view = gerenciar.detalhe(leitor, id);

        assertThat(view.titulo()).isEqualTo("Erro ao gravar o pedido");
        assertThat(view.tipoRotulo()).isEqualTo("Erro");
        assertThat(view.situacaoRotulo()).isEqualTo("Enviado ao desenvolvimento");
        assertThat(view.texto()).isEqualTo("<p>Texto do chamado</p><p>Imagem 1: tela</p>");
        assertThat(view.imagens()).singleElement().satisfies(i -> {
            assertThat(i.arquivo()).isEqualTo("Imagem_01.png");
            assertThat(i.disponivel()).isTrue();
        });
        assertThat(view.historico()).singleElement().satisfies(m -> assertThat(m.por()).isEqualTo("autora"));
        assertThat(view.podeEditar()).isFalse();
        assertThat(gerenciar.detalhe(autora, id).podeEditar()).isTrue();
        assertThat(gerenciar.detalhe(curadora, id).podeEditar()).isTrue();

        Files.delete(imagem);
        GerenciarRegistros.ArquivoDaImagem copia = gerenciar.imagem(leitor, id, 1);
        assertThat(Files.readAllBytes(copia.local())).isEqualTo(PNG);
        assertThat(copia.tipoDeMidia()).isEqualTo("image/png");
        assertThat(copia.local()).startsWith(raiz.resolve("base").resolve(id.toString()));
    }

    @Test
    void quemNaoTemABaseNaoEnviaEOFormularioEConferido() {
        Usuario semBase = base.pessoa("sembase", false, false);

        assertThatThrownBy(() -> confirmar(semBase, formulario("Título", "erro", "enviado_desenvolvimento")))
                .isInstanceOf(AcessoNegadoException.class).extracting("codigo").isEqualTo("SEM_PERMISSAO_NA_BASE");
        assertThatThrownBy(() -> confirmar(autora, formulario(" ", "erro", "enviado_desenvolvimento")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("escreva o título");
        assertThatThrownBy(() -> confirmar(autora, formulario("Título", "erro", "qualquer")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("escolha a situação");
    }

    @Test
    void imagemQueNaoCopiaDesfazTudo() throws Exception {
        Path boa = Files.write(raiz.resolve("boa.png"), PNG);
        Path sumiu = raiz.resolve("sumiu.png");

        assertThatThrownBy(() -> confirmar(autora, formulario("Título", "erro", "enviado_desenvolvimento"), boa, sumiu))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("IMAGEM_NAO_COPIADA");
        assertThat(raiz.resolve("base")).satisfies(p -> {
            if (Files.exists(p)) {
                try (var pastas = Files.list(p)) {
                    assertThat(pastas).isEmpty();
                }
            }
        });
    }

    @Test
    void soOAutorOuQuemCuraMuda() {
        UUID id = confirmar(autora, formulario("Erro ao gravar", "erro", "enviado_desenvolvimento"));
        EdicaoDoRegistro edicao = new EdicaoDoRegistro("Erro ao gravar o pedido", "erro", "Padaria", null, null, null,
                "VEN010", null, null, null, "Acertar o estoque.");

        assertThatThrownBy(() -> gerenciar.editar(leitor, id, 0, edicao))
                .isInstanceOf(AcessoNegadoException.class).hasMessageContaining("Só o autor do registro");
        assertThatThrownBy(() -> gerenciar.mudarSituacao(leitor, id, 0, "corrigido", null, null))
                .isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> gerenciar.validar(leitor, id, 0, true)).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> gerenciar.apagar(leitor, id)).isInstanceOf(AcessoNegadoException.class);

        RegistroView daAutora = gerenciar.editar(autora, id, 0, edicao);
        assertThat(daAutora.titulo()).isEqualTo("Erro ao gravar o pedido");
        assertThat(daAutora.versao()).isEqualTo(1);
        RegistroView daCuradora = gerenciar.validar(curadora, id, 1, true);
        assertThat(daCuradora.solucaoValidada()).isTrue();
        assertThat(daCuradora.validadaPor()).isEqualTo("curadora");
    }

    @Test
    void edicaoSimultanea() {
        UUID id = confirmar(autora, formulario("Erro ao gravar", "erro", "enviado_desenvolvimento"));
        EdicaoDoRegistro daAutora = new EdicaoDoRegistro("Versão da autora", "erro", null, null, null, null, null, null,
                null, null, null);
        EdicaoDoRegistro daCuradora = new EdicaoDoRegistro("Versão da curadora", "erro", null, null, null, null, null,
                null, null, null, null);

        gerenciar.editar(autora, id, 0, daAutora);

        assertThatThrownBy(() -> gerenciar.editar(curadora, id, 0, daCuradora))
                .isInstanceOf(ConflitoException.class).extracting("codigo").isEqualTo("REGISTRO_JA_ALTERADO");
        assertThatThrownBy(() -> gerenciar.mudarSituacao(curadora, id, 0, "corrigido", null, null))
                .isInstanceOf(ConflitoException.class);
        assertThat(gerenciar.detalhe(curadora, id).titulo()).isEqualTo("Versão da autora");
        assertThatThrownBy(() -> gerenciar.editar(autora, id, null, daAutora)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void historicoDaSituacao() {
        UUID id = confirmar(autora, formulario("Erro ao gravar", "erro", "enviado_desenvolvimento"));

        RegistroView enviado = gerenciar.mudarSituacao(autora, id, 0, "enviado_desenvolvimento", " OTRS  4521 ", "9.9");
        assertThat(enviado.chamadoDev()).isEqualTo("OTRS 4521");
        assertThat(enviado.versaoCorrecao()).as("só vale em corrigido").isNull();
        RegistroView corrigido = gerenciar.mudarSituacao(curadora, id, enviado.versao(), "corrigido", "OTRS 4521", "5.3.0");
        RegistroView igual = gerenciar.mudarSituacao(curadora, id, corrigido.versao(), "corrigido", "OTRS 4521", "5.3.0");

        assertThat(igual.versao()).as("nada mudou").isEqualTo(corrigido.versao());
        assertThat(corrigido.situacao()).isEqualTo("corrigido");
        assertThat(corrigido.versaoCorrecao()).isEqualTo("5.3.0");
        assertThat(corrigido.historico()).extracting(RegistroView.Mudanca::para)
                .containsExactly("enviado_desenvolvimento", "enviado_desenvolvimento", "corrigido");
        assertThat(corrigido.historico().get(2)).satisfies(m -> {
            assertThat(m.deRotulo()).isEqualTo("Enviado ao desenvolvimento");
            assertThat(m.paraRotulo()).isEqualTo("Corrigido");
            assertThat(m.por()).isEqualTo("curadora");
            assertThat(m.versaoCorrecao()).isEqualTo("5.3.0");
        });
        assertThatThrownBy(() -> gerenciar.mudarSituacao(autora, id, corrigido.versao(), "talvez", null, null))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("SITUACAO_INVALIDA");
    }

    @Test
    void apagarTiraAsImagensDaPasta() throws Exception {
        Path imagem = Files.write(raiz.resolve("print.png"), PNG);
        UUID id = confirmar(autora, formulario("Erro ao gravar", "erro", "enviado_desenvolvimento"), imagem);

        gerenciar.apagar(curadora, id);

        assertThat(raiz.resolve("base").resolve(id.toString())).doesNotExist();
        assertThatThrownBy(() -> gerenciar.detalhe(autora, id))
                .isInstanceOf(RecursoNaoEncontradoException.class).extracting("codigo").isEqualTo("REGISTRO_NAO_ENCONTRADO");
    }

    @Test
    void pesquisaDaTelaComFiltrosEDestaque() {
        UUID id = confirmar(autora, formulario("Erro ao gravar o pedido com estoque", "erro", "enviado_desenvolvimento"));
        confirmar(autora, formulario("Dúvida sobre o relatório", "duvida", "resolvido_suporte"));

        ResultadoDaPesquisa resultado = pesquisar.pesquisar(leitor, new FiltrosDaPesquisa("ven01", "erro", null, null, null,
                autora.id(), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), false, 1));

        assertThat(resultado.total()).isEqualTo(1);
        assertThat(resultado.comTexto()).isTrue();
        assertThat(resultado.registros()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(id);
            assertThat(r.telaCodigo()).containsExactly(new Trecho.Parte("VEN01", true), new Trecho.Parte("0", false));
            assertThat(r.situacaoRotulo()).isEqualTo("Enviado ao desenvolvimento");
            assertThat(r.trecho()).isNotEmpty();
        });
        assertThat(pesquisar.pesquisar(leitor, new FiltrosDaPesquisa(null, null, null, null, null, autora.id(),
                LocalDate.of(2026, 10, 13), null, false, 1)).total()).isZero();
        assertThatThrownBy(() -> pesquisar.pesquisar(leitor, new FiltrosDaPesquisa(null, "talvez", null, null, null, null,
                null, null, false, 1))).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> pesquisar.pesquisar(leitor, new FiltrosDaPesquisa(null, null, null, null, null, null,
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 12), false, 1)))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("data inicial");
        assertThat(pesquisar.autores()).extracting(PesquisarNaBase.AutorView::id).contains(autora.id());
    }
}
