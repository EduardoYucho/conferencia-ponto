package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.BlocoDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MontagemDoHtml;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.NovaImagem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A tela de resultado sobre o banco e a pasta de verdade: assunto, texto editado, imagens, cópia e .zip. */
class GerenciarSaidasTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 7};

    @TempDir
    Path raiz;

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Saidas saidas = RepositoriosDeTeste.saidas();
    private final ObjectMapper json = new ObjectMapper();
    private GerenciarSaidas gerenciar;
    private Usuario maria;
    private Usuario joao;
    private UUID atendimento;
    private UUID saida;
    private final UUID imagem1 = UUID.randomUUID();
    private final UUID imagem2 = UUID.randomUUID();

    private static Usuario usuario(String login) {
        return new Usuario(PostgresDeTeste.novoUsuario(true), login, login, "hash", true, Set.of(Perfil.ROLE_USER), null,
                null, false);
    }

    @BeforeEach
    void preparar() throws Exception {
        gerenciar = new GerenciarSaidas(RepositoriosDeTeste.atendimentos(), saidas, new PastaEmDisco(raiz),
                new ConfiguracaoDaRedacao("m", 3, 1000, null, null, Duration.ofHours(1), 1024), json);
        maria = usuario("maria");
        joao = usuario("joao");
        atendimento = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, contato, conversa, versao_leitor,
                               apagar_arquivos_em, apagar_textos_em, situacao)
                        VALUES (?, ?, '1', 'Maria', '{}'::jsonb, 1, now(), now(), 'pronto')
                        """).params(atendimento, maria.id()).update();
        Files.createDirectories(raiz.resolve(atendimento + "/arquivos"));
        Files.write(raiz.resolve(atendimento + "/arquivos/001_print.png"), PNG);
        Files.write(raiz.resolve(atendimento + "/arquivos/002_foto.jpeg"), JPEG);
        saida = saidas.criar(atendimento, TipoDeSaida.RESUMO, EnumSet.of(SituacaoDaSaida.FALHOU), AGORA).orElseThrow();
        List<BlocoDoTexto> blocos = List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "O cliente relatou o erro.", List.of("M1", "A1"),
                        List.of(new BlocoDoTexto.ImagemCitada("A1", imagem1, 1, "tela de pedidos"))),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "Alterei o desconto de 10% para 5%.", List.of("M4", "A2"),
                        List.of(new BlocoDoTexto.ImagemCitada("A2", imagem2, 2, "mensagem de erro"))));
        TextoRedigido texto = new TextoRedigido(1, "Digisac: Maria : erro", blocos, Map.of("cliente", "Loja"),
                "Pedidos (VEN010)", List.of("1 trecho veio sem fonte"));
        saidas.salvarRedacao(saida, EstruturaDaSaida.escrever(json, texto), MontagemDoHtml.montar(blocos), texto.assunto(), "m",
                1, 100, 20, List.of(new NovaImagem(imagem1, 1, null, atendimento + "/arquivos/001_print.png", "tela de pedidos"),
                        new NovaImagem(imagem2, 2, null, atendimento + "/arquivos/002_foto.jpeg", "mensagem de erro")));
    }

    @Test
    void abreOTextoComAsImagensEOsAvisosSoParaODono() {
        SaidaView view = gerenciar.detalhe(maria, atendimento, saida);

        assertThat(view.situacao()).isEqualTo("pronta");
        assertThat(view.editado()).isFalse();
        assertThat(view.avisos()).containsExactly("1 trecho veio sem fonte");
        assertThat(view.base()).containsEntry("cliente", "Loja");
        assertThat(view.imagens()).extracting(SaidaView.Imagem::arquivo).containsExactly("Imagem_01.png", "Imagem_02.jpeg");
        assertThat(view.imagens()).allMatch(SaidaView.Imagem::disponivel);
        assertThat(gerenciar.listar(maria, atendimento)).singleElement().satisfies(s -> assertThat(s.id()).isEqualTo(saida));

        assertThatThrownBy(() -> gerenciar.detalhe(joao, atendimento, saida)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> gerenciar.detalhe(maria, atendimento, UUID.randomUUID()))
                .isInstanceOf(RecursoNaoEncontradoException.class).hasMessageContaining("Texto não encontrado");
    }

    @Test
    void assuntoAte150Caracteres() {
        assertThatThrownBy(() -> gerenciar.salvarAssunto(maria, atendimento, saida, "x".repeat(151)))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("151 caracteres");
        assertThatThrownBy(() -> gerenciar.salvarAssunto(maria, atendimento, saida, "  ")).isInstanceOf(RegraNegocioException.class);

        assertThat(gerenciar.salvarAssunto(maria, atendimento, saida, "  Digisac:   Maria : outro  ").assunto())
                .isEqualTo("Digisac: Maria : outro");
    }

    @Test
    void textoEditadoELimpoRenumeraAsImagensEDaParaDesfazer() {
        String editado = "<p data-imagem=\"" + imagem2 + "\">Imagem 2: o erro</p><p onclick=\"x\">Novo parágrafo"
                + "<script>alert(1)</script></p><p data-fontes=\"M1\">O cliente relatou.</p>";

        SaidaView view = gerenciar.salvarTexto(maria, atendimento, saida, editado);

        assertThat(view.editado()).isTrue();
        assertThat(view.html()).doesNotContain("script").doesNotContain("onclick")
                .startsWith("<p data-imagem=\"" + imagem2 + "\"><strong>Imagem 1</strong>: o erro</p>");
        assertThat(view.imagens()).extracting(SaidaView.Imagem::id, SaidaView.Imagem::numero, SaidaView.Imagem::incluida,
                SaidaView.Imagem::arquivo).containsExactly(
                org.assertj.core.groups.Tuple.tuple(imagem2, 1, true, "Imagem_01.jpeg"),
                org.assertj.core.groups.Tuple.tuple(imagem1, 2, false, "Imagem_02.png"));
        assertThatThrownBy(() -> gerenciar.salvarTexto(maria, atendimento, saida, "<script>x</script>"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("vazio");

        SaidaView desfeito = gerenciar.desfazerEdicao(maria, atendimento, saida);
        assertThat(desfeito.editado()).isFalse();
        assertThat(desfeito.html()).contains("<strong>Imagem 1</strong>: tela de pedidos");
        assertThat(desfeito.imagens()).extracting(SaidaView.Imagem::id, SaidaView.Imagem::numero, SaidaView.Imagem::legenda)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(imagem1, 1, "tela de pedidos"),
                        org.assertj.core.groups.Tuple.tuple(imagem2, 2, "mensagem de erro"));
    }

    @Test
    void reordenaETiraImagensEOServidorRenumera() {
        SaidaView trocadas = gerenciar.ordenarImagens(maria, atendimento, saida, List.of(imagem2, imagem1));
        assertThat(trocadas.html()).contains("<p data-imagem=\"" + imagem2 + "\"><strong>Imagem 1</strong>: mensagem de erro</p>");
        assertThat(trocadas.imagens()).extracting(SaidaView.Imagem::id).containsExactly(imagem2, imagem1);

        SaidaView umaSo = gerenciar.ordenarImagens(maria, atendimento, saida, List.of(imagem1));
        assertThat(umaSo.imagens()).extracting(SaidaView.Imagem::numero, SaidaView.Imagem::incluida)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, true), org.assertj.core.groups.Tuple.tuple(2, false));
        assertThat(umaSo.html()).doesNotContain(imagem2.toString());

        assertThatThrownBy(() -> gerenciar.ordenarImagens(maria, atendimento, saida, List.of(imagem1, imagem1)))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> gerenciar.ordenarImagens(maria, atendimento, saida, List.of(UUID.randomUUID())))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void copiaNosTresModosEZipNaOrdemDoTexto() throws Exception {
        CopiaView com = gerenciar.copia(maria, atendimento, saida, ModoDeCopia.COM_IMAGENS);
        assertThat(com.html()).contains("<img src=\"data:image/png;base64,").contains("<img src=\"data:image/jpeg;base64,")
                .doesNotContain("data-fontes");
        assertThat(com.avisos()).isEmpty();
        CopiaView so = gerenciar.copia(maria, atendimento, saida, ModoDeCopia.SO_TEXTO);
        assertThat(so.html()).doesNotContain("<img").contains("<strong>Imagem 1</strong>: tela de pedidos");
        assertThat(so.texto()).startsWith("O cliente relatou o erro.\n\nImagem 1: tela de pedidos\n\nAlterei o desconto");
        assertThat(gerenciar.copia(maria, atendimento, saida, ModoDeCopia.CODIGO_FONTE).html()).isEqualTo(so.html());

        List<GerenciarSaidas.ArquivoDaImagem> zip = gerenciar.imagensParaOZip(maria, atendimento, saida);
        assertThat(zip).extracting(GerenciarSaidas.ArquivoDaImagem::nome).containsExactly("Imagem_01.png", "Imagem_02.jpeg");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GerenciarSaidas.escreverZip(zip, bytes);
        assertThat(bytes.size()).isGreaterThan(PNG.length + JPEG.length);
        assertThat(gerenciar.imagem(maria, atendimento, saida, imagem2).tipoDeMidia()).isEqualTo("image/jpeg");
    }

    @Test
    void imagensApagadasPelaRetencaoOuGrandesDemaisAvisam() throws Exception {
        Files.write(raiz.resolve(atendimento + "/arquivos/002_foto.jpeg"), new byte[2048]); // acima de 1 KB
        Files.delete(raiz.resolve(atendimento + "/arquivos/001_print.png"));

        CopiaView com = gerenciar.copia(maria, atendimento, saida, ModoDeCopia.COM_IMAGENS);
        assertThat(com.html()).doesNotContain("<img");
        assertThat(com.avisos()).containsExactly(
                "A imagem 1 não está mais no servidor (prazo de retenção) e foi só a legenda.",
                "A imagem 2 é grande demais para ir na cópia (0 MB): anexe o arquivo Imagem_02.jpeg.");
        assertThat(gerenciar.imagensParaOZip(maria, atendimento, saida)).hasSize(1);
        assertThatThrownBy(() -> gerenciar.imagem(maria, atendimento, saida, imagem1))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("apagados");

        Files.delete(raiz.resolve(atendimento + "/arquivos/002_foto.jpeg"));
        assertThat(gerenciar.detalhe(maria, atendimento, saida).imagensApagadas()).isTrue();
        assertThatThrownBy(() -> gerenciar.imagensParaOZip(maria, atendimento, saida))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("apagados");
    }

    @Test
    void textoQueAindaNaoFicouProntoNaoSeMexe() {
        UUID outra = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.saida (id, atendimento_id, tipo, versao, situacao)
                        VALUES (?, ?, 'resumo', 9, 'redigindo')
                        """).params(outra, atendimento).update();

        assertThatThrownBy(() -> gerenciar.salvarAssunto(maria, atendimento, outra, "x"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("sendo gerado");
        assertThatThrownBy(() -> gerenciar.copia(maria, atendimento, outra, ModoDeCopia.SO_TEXTO))
                .isInstanceOf(RegraNegocioException.class);
        assertThat(gerenciar.detalhe(maria, atendimento, outra).situacao()).isEqualTo("redigindo");
    }
}
