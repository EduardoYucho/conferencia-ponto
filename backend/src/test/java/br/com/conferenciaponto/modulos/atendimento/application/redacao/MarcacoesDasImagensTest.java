package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.BlocoDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MontagemDoHtml;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.NovaImagem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.imagem.MarcadorJava2D;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As marcações em vermelho (experimental): as áreas da análise viram uma borda numa cópia, e a cópia passa a valer na
 * tela, na cópia para o chamado, no .zip e na base; o original não muda.
 */
class MarcacoesDasImagensTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");
    private static final String COM_AREAS = """
            {"descricao":"Tela de pedidos com erro","regioes":[
              {"descricao":"mensagem de erro","caixa":[300,250,500,500]},
              {"descricao":"caixa inválida","caixa":[900,900,100,100]},
              {"descricao":"incompleta","caixa":[1,2]}]}
            """;

    @TempDir
    Path raiz;

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Saidas saidas = RepositoriosDeTeste.saidas();
    private final Analises analises = RepositoriosDeTeste.analises();
    private final ObjectMapper json = new ObjectMapper();
    private final List<Object> eventos = new ArrayList<>();
    private GerenciarSaidas gerenciar;
    private Usuario maria;
    private UUID atendimento;
    private UUID saida;
    private final UUID print = UUID.randomUUID();
    private final UUID foto = UUID.randomUUID();
    private final UUID quadro = UUID.randomUUID();
    private UUID arquivoDoPrint;
    private UUID arquivoDaFoto;

    private GerenciarSaidas comMarcacoes(boolean ligadas) {
        return new GerenciarSaidas(RepositoriosDeTeste.atendimentos(), saidas, new PastaEmDisco(raiz),
                new ConfiguracaoDaRedacao("m", 3, 1000, null, null, Duration.ofHours(1), 10 * 1024 * 1024), json,
                eventos::add, TransactionOperations.withoutTransaction(), Clock.fixed(AGORA, ZoneOffset.UTC), analises,
                new MarcadorJava2D(), ligadas);
    }

    private UUID arquivo(int ordem, String nome) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO atendimento.arquivo (id, atendimento_id, origem, ordem, nome_original) VALUES (?, ?, 'anexo_conversa', ?, ?)")
                .params(id, atendimento, ordem, nome).update();
        return id;
    }

    private void imagemCinza(String caminho, String formato) throws Exception {
        BufferedImage imagem = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 400; x++) {
            for (int y = 0; y < 300; y++) {
                imagem.setRGB(x, y, new Color(200, 200, 200).getRGB());
            }
        }
        ImageIO.write(imagem, formato, raiz.resolve(caminho).toFile());
    }

    @BeforeEach
    void preparar() throws Exception {
        gerenciar = comMarcacoes(true);
        maria = new Usuario(PostgresDeTeste.novoUsuario(true), "maria", "maria", "hash", true, Acesso.usuario(),
                null, null, false);
        atendimento = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, contato, conversa, versao_leitor,
                               apagar_arquivos_em, apagar_textos_em, situacao)
                        VALUES (?, ?, '1', 'Maria', '{}'::jsonb, 1, now(), now(), 'pronto')
                        """).params(atendimento, maria.id()).update();
        Files.createDirectories(raiz.resolve(atendimento + "/arquivos"));
        imagemCinza(atendimento + "/arquivos/001_print.png", "png");
        imagemCinza(atendimento + "/arquivos/002_foto.jpeg", "jpeg");
        imagemCinza(atendimento + "/arquivos/003_quadro.png", "png");
        arquivoDoPrint = arquivo(1, "print.png");
        arquivoDaFoto = arquivo(2, "foto.jpeg");
        UUID arquivoDoVideo = arquivo(3, "video.mp4");
        analises.salvar(arquivoDoPrint, 1, "m", COM_AREAS, 10, 10, 10, AGORA);
        analises.salvar(arquivoDaFoto, 1, "m", "{\"descricao\":\"foto da tela\",\"regioes\":[]}", 10, 10, 10, AGORA);

        saida = saidas.criar(atendimento, TipoDeSaida.RESUMO, EnumSet.of(SituacaoDaSaida.FALHOU), AGORA).orElseThrow();
        List<BlocoDoTexto> blocos = List.of(
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "O cliente relatou o erro.", List.of("M1", "A1"),
                        List.of(new BlocoDoTexto.ImagemCitada("A1", print, 1, "tela de pedidos"))),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Mandou uma foto.", List.of("A2"),
                        List.of(new BlocoDoTexto.ImagemCitada("A2", foto, 2, "foto"))),
                new BlocoDoTexto(BlocoDoTexto.Tipo.PASSO, "No vídeo, o erro aparece.", List.of("A3"),
                        List.of(new BlocoDoTexto.ImagemCitada("A3", quadro, 3, "quadro do vídeo"))));
        TextoRedigido texto = new TextoRedigido(1, "Digisac: Maria : erro", blocos, Map.of(), "Pedidos (VEN010)", List.of());
        saidas.salvarRedacao(saida, EstruturaDaSaida.escrever(json, texto), MontagemDoHtml.montar(blocos), texto.assunto(), "m",
                1, 100, 20, List.of(
                        new NovaImagem(print, 1, arquivoDoPrint, atendimento + "/arquivos/001_print.png", "tela de pedidos"),
                        new NovaImagem(foto, 2, arquivoDaFoto, atendimento + "/arquivos/002_foto.jpeg", "foto"),
                        new NovaImagem(quadro, 3, arquivoDoVideo, atendimento + "/arquivos/003_quadro.png", "quadro", 4_000L)));
    }

    private Path marcada(UUID imagem) {
        return raiz.resolve(atendimento + "/arquivos/marcadas/" + imagem + ".png");
    }

    private static boolean vermelho(Path arquivo, int x, int y) throws Exception {
        Color c = new Color(ImageIO.read(arquivo.toFile()).getRGB(x, y));
        return c.getRed() > 200 && c.getGreen() < 40 && c.getBlue() < 40;
    }

    private SaidaView.Imagem naView(SaidaView view, UUID id) {
        return view.imagens().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void cadaImagemComecaSemMarcacaoESoAsComAnaliseDeImagemPodemMarcar() {
        SaidaView view = gerenciar.detalhe(maria, atendimento, saida);

        assertThat(view.imagens()).noneMatch(SaidaView.Imagem::comMarcacao);
        assertThat(naView(view, print).podeMarcar()).isTrue();
        assertThat(naView(view, foto).podeMarcar()).isTrue();
        assertThat(naView(view, quadro).podeMarcar()).as("quadro de vídeo").isFalse();
    }

    @Test
    void ligarDesenhaAsAreasDaAnaliseNumaCopiaQuePassaAValer() throws Exception {
        Path original = raiz.resolve(atendimento + "/arquivos/001_print.png");
        byte[] antes = Files.readAllBytes(original);

        SaidaView view = gerenciar.marcar(maria, atendimento, saida, print, true);

        assertThat(naView(view, print).comMarcacao()).isTrue();
        assertThat(naView(view, print).arquivo()).isEqualTo("Imagem_01.png");
        assertThat(Files.readAllBytes(original)).as("o original não muda").isEqualTo(antes);
        assertThat(vermelho(marcada(print), 150, 85)).as("borda em volta da mensagem de erro").isTrue();
        assertThat(vermelho(marcada(print), 150, 120)).as("dentro da área").isFalse();
        Map<String, Object> linha = jdbc.sql("SELECT marcacoes::text AS m, com_marcacao, largura, altura FROM atendimento.imagem WHERE id = ?")
                .param(print).query().singleRow();
        assertThat(linha.get("com_marcacao")).isEqualTo(true);
        assertThat(linha.get("largura")).isEqualTo(400);
        assertThat(linha.get("altura")).isEqualTo(300);
        assertThat((String) linha.get("m")).contains("mensagem de erro").doesNotContain("caixa inválida").doesNotContain("incompleta");

        assertThat(gerenciar.imagem(maria, atendimento, saida, print).local()).isEqualTo(marcada(print));
        assertThat(gerenciar.imagensParaOZip(maria, atendimento, saida))
                .extracting(GerenciarSaidas.ArquivoDaImagem::nome, GerenciarSaidas.ArquivoDaImagem::local)
                .contains(org.assertj.core.groups.Tuple.tuple("Imagem_01.png", marcada(print)));
    }

    @Test
    void desligarVoltaAoOriginalEApagaACopia() {
        gerenciar.marcar(maria, atendimento, saida, print, true);

        SaidaView view = gerenciar.marcar(maria, atendimento, saida, print, false);

        assertThat(naView(view, print).comMarcacao()).isFalse();
        assertThat(marcada(print)).doesNotExist();
        assertThat(gerenciar.imagem(maria, atendimento, saida, print).local())
                .isEqualTo(raiz.resolve(atendimento + "/arquivos/001_print.png"));
        // desligar de novo não faz mal
        assertThat(naView(gerenciar.marcar(maria, atendimento, saida, print, false), print).comMarcacao()).isFalse();
    }

    @Test
    void ligarDeNovoUsaAsAreasGuardadasMesmoComAnaliseNova() throws Exception {
        gerenciar.marcar(maria, atendimento, saida, print, true);
        gerenciar.marcar(maria, atendimento, saida, print, false);
        analises.salvar(arquivoDoPrint, 2, "m", "{\"regioes\":[{\"descricao\":\"outra\",\"caixa\":[0,0,100,100]}]}", 1, 1, 1,
                AGORA.plusSeconds(60));

        gerenciar.marcar(maria, atendimento, saida, print, true);

        assertThat(vermelho(marcada(print), 150, 85)).isTrue();
    }

    @Test
    void semAreasNaoMarca() {
        assertThatThrownBy(() -> gerenciar.marcar(maria, atendimento, saida, foto, true))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("SEM_AREAS");
        assertThatThrownBy(() -> gerenciar.marcar(maria, atendimento, saida, quadro, true))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("quadros do vídeo");
        assertThat(marcada(foto)).doesNotExist();
        assertThat(naView(gerenciar.detalhe(maria, atendimento, saida), foto).comMarcacao()).isFalse();
    }

    @Test
    void aFotoEmJpegMarcadaViraPngNaCopiaParaOChamado() {
        analises.salvar(arquivoDaFoto, 2, "m", COM_AREAS, 1, 1, 1, AGORA.plusSeconds(60));

        SaidaView view = gerenciar.marcar(maria, atendimento, saida, foto, true);

        assertThat(naView(view, foto).arquivo()).isEqualTo("Imagem_02.png");
        CopiaView copia = gerenciar.copia(maria, atendimento, saida, ModoDeCopia.COM_IMAGENS);
        assertThat(copia.html()).contains("data:image/png;base64").doesNotContain("data:image/jpeg");
        assertThat(gerenciar.imagem(maria, atendimento, saida, foto).tipoDeMidia()).isEqualTo("image/png");
    }

    @Test
    void aBaseRecebeACopiaMarcada() {
        gerenciar.marcar(maria, atendimento, saida, print, true);

        gerenciar.confirmar(maria, atendimento, saida, new TextoConfirmado.Formulario("Erro ao gravar", "erro", "Loja",
                "Maria", null, "Pedidos", "VEN010", "Não grava", null, null, "Acertar", "resolvido", null));

        assertThat(eventos).singleElement().isInstanceOfSatisfying(TextoConfirmado.class, evento ->
                assertThat(evento.imagens()).extracting(TextoConfirmado.Imagem::numero, TextoConfirmado.Imagem::arquivo)
                        .contains(org.assertj.core.groups.Tuple.tuple(1, marcada(print))));
        assertThatThrownBy(() -> gerenciar.marcar(maria, atendimento, saida, print, false))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("TEXTO_CONFIRMADO");
        assertThat(marcada(print)).as("a cópia que a base recebeu continua").exists();
    }

    @Test
    void desligadasNoServidorOuDeOutraPessoa() {
        GerenciarSaidas semMarcacoes = comMarcacoes(false);
        assertThat(semMarcacoes.detalhe(maria, atendimento, saida).imagens()).noneMatch(SaidaView.Imagem::podeMarcar);
        assertThatThrownBy(() -> semMarcacoes.marcar(maria, atendimento, saida, print, true))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("MARCACOES_DESLIGADAS");

        Usuario joao = new Usuario(PostgresDeTeste.novoUsuario(true), "joao", "joao", "hash", true, Acesso.usuario(),
                null, null, false);
        assertThatThrownBy(() -> gerenciar.marcar(joao, atendimento, saida, print, true))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> gerenciar.marcar(maria, atendimento, saida, UUID.randomUUID(), true))
                .isInstanceOf(RecursoNaoEncontradoException.class).hasMessageContaining("Imagem não encontrada");
        assertThat(marcada(print)).doesNotExist();
    }

    @Test
    void imagemQueOServidorNaoAbreVemComMensagem() throws Exception {
        Files.write(raiz.resolve(atendimento + "/arquivos/001_print.png"), new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0});

        assertThatThrownBy(() -> gerenciar.marcar(maria, atendimento, saida, print, true))
                .isInstanceOf(RegraNegocioException.class).extracting("codigo").isEqualTo("MARCACAO_FALHOU");
        assertThat(naView(gerenciar.detalhe(maria, atendimento, saida), print).comMarcacao()).isFalse();
    }
}
