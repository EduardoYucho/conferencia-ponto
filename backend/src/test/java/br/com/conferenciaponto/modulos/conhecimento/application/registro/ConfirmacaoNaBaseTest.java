package br.com.conferenciaponto.modulos.conhecimento.application.registro;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ConfiguracaoDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.GerenciarSaidas;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ModoDeCopia;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidaView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidasDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.retencao.LimpezaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.BlocoDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.NovaImagem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.armazenamento.PastaDaBaseEmDisco;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence.RepositoriosDaBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Do gerador para a base, com o banco e a pasta de verdade: confirmar o texto cria o registro (com o formulário
 * revisado e a cópia das imagens) e trava o texto; se a base recusar, nada muda; e o registro continua inteiro depois
 * que a retenção apaga o atendimento.
 */
class ConfirmacaoNaBaseTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo"));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 7};

    @TempDir
    Path raiz;

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Saidas saidas = RepositoriosDeTeste.saidas();
    private final Registros registros = RepositoriosDaBase.registros();
    private final ParaOsTestesDaBase base = new ParaOsTestesDaBase(RELOGIO);
    private GerenciarSaidas gerador;
    private GerenciarRegistros naBase;
    private Usuario maria;
    private UUID atendimento;
    private UUID saida;

    @BeforeEach
    void preparar() throws Exception {
        PastaDaBaseEmDisco pastaDaBase = new PastaDaBaseEmDisco(raiz);
        RegistrarTextoConfirmado registrar = new RegistrarTextoConfirmado(registros, pastaDaBase,
                base.usuarios);
        gerador = new GerenciarSaidas(RepositoriosDeTeste.atendimentos(), saidas, new PastaEmDisco(raiz),
                new ConfiguracaoDaRedacao("m", 3, 1000, null, null, Duration.ofHours(1), 1024), new ObjectMapper(),
                evento -> {
                    if (evento instanceof TextoConfirmado texto) {
                        registrar.aoConfirmar(texto);
                    }
                }, RepositoriosDeTeste.transacao(), RELOGIO);
        naBase = new GerenciarRegistros(registros, pastaDaBase, RELOGIO);
        maria = base.pessoa("maria", true, false);
        atendimento = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, contato, conversa, versao_leitor,
                               apagar_arquivos_em, apagar_textos_em, situacao)
                        VALUES (?, ?, '1', 'Maria', '{}'::jsonb, 1, now(), now() + interval '180 days', 'pronto')
                        """).params(atendimento, maria.id()).update();
        Files.createDirectories(raiz.resolve(atendimento + "/arquivos"));
        Files.write(raiz.resolve(atendimento + "/arquivos/001_print.png"), PNG);
        Files.write(raiz.resolve(atendimento + "/arquivos/002_foto.jpeg"), JPEG);
        saida = redigir(TipoDeSaida.DEV_ERRO);
    }

    private UUID redigir(TipoDeSaida tipo) {
        UUID imagem1 = UUID.randomUUID();
        UUID imagem2 = UUID.randomUUID();
        List<BlocoDoTexto> blocos = List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Ao gravar o pedido aparece o erro.", List.of("M1", "A1"),
                        List.of(new BlocoDoTexto.ImagemCitada("A1", imagem1, 1, "tela de pedidos"))),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "Gravei o pedido com o produto sem estoque.", List.of("M4", "A2"),
                        List.of(new BlocoDoTexto.ImagemCitada("A2", imagem2, 2, "mensagem de erro"))));
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("cliente", "Padaria São João");
        campos.put("modulo", "Vendas");
        campos.put("tela", "Pedidos de venda");
        campos.put("codigoTela", "VEN010");
        campos.put("sintoma", "O pedido não grava.");
        campos.put("mensagemErro", "não identificado");
        campos.put("causa", "Estoque negativo.");
        campos.put("resolucao", "Acertar o estoque.");
        TextoRedigido texto = new TextoRedigido(1, "Digisac: Maria : Erro ao gravar o pedido", blocos, campos,
                "Pedidos de venda (VEN010)", List.of());
        return SaidasDeTeste.pronta(saidas, atendimento, tipo, texto, List.of(
                new NovaImagem(imagem1, 1, null, atendimento + "/arquivos/001_print.png", "tela de pedidos"),
                new NovaImagem(imagem2, 2, null, atendimento + "/arquivos/002_foto.jpeg", "mensagem de erro")), AGORA);
    }

    @Test
    void oFormularioVemPreenchido() {
        TextoConfirmado.Formulario registro = gerador.detalhe(maria, atendimento, saida).registro();

        assertThat(registro).isEqualTo(new TextoConfirmado.Formulario("Erro ao gravar o pedido", "erro", "Padaria São João",
                "Maria", "Vendas", "Pedidos de venda", "VEN010", "O pedido não grava.", null, "Estoque negativo.",
                "Acertar o estoque.", "enviado_desenvolvimento", null));
    }

    @Test
    void confirmarCriaORegistroETravaOTexto() {
        TextoConfirmado.Formulario revisado = new TextoConfirmado.Formulario("Erro ao gravar pedido sem estoque", "erro",
                "Padaria São João", "Maria", "Vendas", "Pedidos de venda", "VEN010", "O pedido não grava.",
                "Estoque insuficiente", "Estoque negativo.", "Acertar o estoque.", "enviado_desenvolvimento", "OTRS 77");

        GerenciarSaidas.ConfirmacaoView confirmacao = gerador.confirmar(maria, atendimento, saida, revisado);

        assertThat(confirmacao.avisos()).isEmpty();
        assertThat(confirmacao.saida().situacao()).isEqualTo("confirmada");
        assertThat(confirmacao.saida().confirmadaEm()).isEqualTo(AGORA);
        assertThat(confirmacao.saida().registro()).as("o formulário só vale antes").isNull();

        RegistroView registro = naBase.detalhe(maria, naBase.daSaida(saida));
        assertThat(registro.titulo()).isEqualTo("Erro ao gravar pedido sem estoque");
        assertThat(registro.mensagemErro()).isEqualTo("Estoque insuficiente");
        assertThat(registro.chamadoDev()).isEqualTo("OTRS 77");
        assertThat(registro.atendimentoId()).isEqualTo(atendimento);
        assertThat(registro.texto()).contains("Tela/Funcionalidade").contains("Imagem 1").contains("Imagem 2")
                .doesNotContain("data-fontes").doesNotContain("data-imagem");
        assertThat(registro.imagens()).extracting(RegistroView.Imagem::arquivo).containsExactly("Imagem_01.png", "Imagem_02.jpeg");
        assertThat(registro.imagens()).extracting(RegistroView.Imagem::legenda).containsExactly("tela de pedidos", "mensagem de erro");

        assertThatThrownBy(() -> gerador.salvarAssunto(maria, atendimento, saida, "Outro"))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("TEXTO_CONFIRMADO");
        assertThatThrownBy(() -> gerador.salvarTexto(maria, atendimento, saida, "<p>outro</p>"))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("TEXTO_CONFIRMADO");
        assertThat(gerador.copia(maria, atendimento, saida, ModoDeCopia.SO_TEXTO).html()).contains("Imagem 1");
        assertThat(gerador.imagensParaOZip(maria, atendimento, saida)).hasSize(2);
        assertThatThrownBy(() -> gerador.confirmar(maria, atendimento, saida, revisado))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("já foi enviado para a base em 12/10/2026 às 12:00");
    }

    @Test
    void seABaseRecusaOTextoContinuaPronto() {
        Usuario semBase = base.pessoa("sembase", false, false);
        jdbc.sql("UPDATE atendimento.atendimento SET usuario_id = ? WHERE id = ?").params(semBase.id(), atendimento).update();
        TextoConfirmado.Formulario formulario = gerador.detalhe(semBase, atendimento, saida).registro();

        assertThatThrownBy(() -> gerador.confirmar(semBase, atendimento, saida, formulario))
                .isInstanceOf(AcessoNegadoException.class).extracting("codigo").isEqualTo("SEM_PERMISSAO_NA_BASE");
        assertThat(gerador.detalhe(semBase, atendimento, saida).situacao()).isEqualTo("pronta");
        assertThat(registros.daSaida(saida)).isEmpty();

        jdbc.sql("UPDATE atendimento.atendimento SET usuario_id = ? WHERE id = ?").params(maria.id(), atendimento).update();
        TextoConfirmado.Formulario longo = new TextoConfirmado.Formulario("Título", "erro", null, null, null, null,
                "X".repeat(41), null, null, null, null, "enviado_desenvolvimento", null);
        assertThatThrownBy(() -> gerador.confirmar(maria, atendimento, saida, longo))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("o código da tela tem 41 caracteres");
        assertThat(gerador.detalhe(maria, atendimento, saida).situacao()).isEqualTo("pronta");
        assertThat(registros.daSaida(saida)).isEmpty();
        assertThat(raiz.resolve("base")).satisfies(p -> {
            if (Files.exists(p)) {
                try (var pastas = Files.list(p)) {
                    assertThat(pastas).as("as cópias saem junto").isEmpty();
                }
            }
        });
    }

    @Test
    void imagemQueJaFoiApagadaNaoVaiEAvisa() throws Exception {
        Files.delete(raiz.resolve(atendimento + "/arquivos/002_foto.jpeg"));

        GerenciarSaidas.ConfirmacaoView confirmacao = gerador.confirmar(maria, atendimento, saida,
                gerador.detalhe(maria, atendimento, saida).registro());

        assertThat(confirmacao.avisos()).containsExactly("A imagem 2 não está mais no servidor (prazo de retenção) e não foi "
                + "para a base.");
        assertThat(naBase.detalhe(maria, naBase.daSaida(saida)).imagens()).extracting(RegistroView.Imagem::numero)
                .containsExactly(1);
    }

    @Test
    void outraVersaoJaNaBaseApareceNaSeguinte() {
        gerador.confirmar(maria, atendimento, saida, gerador.detalhe(maria, atendimento, saida).registro());

        UUID segunda = redigir(TipoDeSaida.DEV_ERRO);
        SaidaView view = gerador.detalhe(maria, atendimento, segunda);

        assertThat(view.versao()).isEqualTo(2);
        assertThat(view.naBase()).isEqualTo(new SaidaView.VersaoNaBase(saida, 1, AGORA));
        assertThat(gerador.detalhe(maria, atendimento, saida).naBase()).as("a própria não conta").isNull();
    }

    @Test
    void oRegistroContinuaInteiroDepoisQueARetencaoApagaOAtendimento() throws Exception {
        gerador.confirmar(maria, atendimento, saida, gerador.detalhe(maria, atendimento, saida).registro());
        UUID id = naBase.daSaida(saida);
        Instant longeNoPassado = Instant.parse("2001-01-01T00:00:00Z");
        jdbc.sql("UPDATE atendimento.atendimento SET apagar_textos_em = ?, apagar_arquivos_em = ? WHERE id = ?")
                .params(java.time.OffsetDateTime.ofInstant(longeNoPassado, ZoneOffset.UTC),
                        java.time.OffsetDateTime.ofInstant(longeNoPassado, ZoneOffset.UTC), atendimento).update();

        LimpezaDosAtendimentos.Resultado limpeza = new LimpezaDosAtendimentos(RepositoriosDeTeste.atendimentos(),
                RepositoriosDeTeste.arquivos(), RepositoriosDeTeste.tarefas(), new PastaEmDisco(raiz),
                Clock.fixed(longeNoPassado.plusSeconds(60), ZoneOffset.UTC)).limpar();

        assertThat(limpeza.atendimentosApagados()).isEqualTo(1);
        assertThat(raiz.resolve(atendimento.toString())).doesNotExist();
        assertThat(saidas.buscar(saida)).isEmpty();
        RegistroView registro = naBase.detalhe(maria, id);
        assertThat(registro.titulo()).isEqualTo("Erro ao gravar o pedido");
        assertThat(registro.texto()).contains("Ao gravar o pedido aparece o erro.");
        assertThat(registro.imagens()).allMatch(RegistroView.Imagem::disponivel);
        assertThat(Files.readAllBytes(naBase.imagem(maria, id, 1).local())).isEqualTo(PNG);
        assertThat(naBase.daSaida(saida)).isEqualTo(id);
    }
}
