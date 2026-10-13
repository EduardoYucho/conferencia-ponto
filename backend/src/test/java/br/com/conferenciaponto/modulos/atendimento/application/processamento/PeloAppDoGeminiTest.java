package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ServidorDeAnexos;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.analise.FilaDeAnalises;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ParaOsTestes;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.fila.CoordenadorDaFila;
import br.com.conferenciaponto.modulos.atendimento.application.ia.ChamadasAoGemini;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ConfiguracaoDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.FilaDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.GerenciarSaidas;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.MontarLinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.PeloAppDoGemini;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.RedigirSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidaView;
import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ConversorDeMidia;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.BaixadorHttp;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.PoliticaDeRede;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.JuntadorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.ExtratorQuadrosFfmpeg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * O texto pelo app do Gemini: os anexos baixados (sem análise, como quando a cota acaba) viram o pedido e os
 * arquivos para anexar, e a resposta colada passa pela conferência e vira o texto (banco, pasta e fila de verdade).
 */
class PeloAppDoGeminiTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final String CHAVE = "AQ.Ab8RN6Kc-chave_de-teste.0123456789wxyz";
    private static final byte[] OGG = "OggS\0\2\0\0\0\0\0\0opus".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MP4 = "\0\0\0\u0018ftypisom\0\0\0\0video".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path raiz;

    private final ServidorDeAnexos servidor = new ServidorDeAnexos();
    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();
    private final Arquivos arquivos = RepositoriosDeTeste.arquivos();
    private final Tarefas tarefas = RepositoriosDeTeste.tarefas();
    private final Analises analises = RepositoriosDeTeste.analises();
    private final Saidas saidas = RepositoriosDeTeste.saidas();
    private final ChavesGemini chaves = RepositoriosDeTeste.chaves();
    private final ModeloDeMentira modelo = new ModeloDeMentira();
    private final ObjectMapper json = new ObjectMapper();
    private final ConfiguracaoDaAnalise configuracao = new ConfiguracaoDaAnalise("gemini-3.8-flash", "gemini-3.8-flash", 3,
            15 * 1024 * 1024, 32768, null, null);
    private final ConfiguracaoDaRedacao configuracaoDaRedacao = new ConfiguracaoDaRedacao("gemini-3.8-flash", 3, 32768, null,
            null, Duration.ofHours(1), 10 * 1024 * 1024);
    private final FilaDeAnalises fila = new FilaDeAnalises(analises, tarefas, configuracao, chaves);
    private final FilaDaRedacao redacao = new FilaDaRedacao(saidas, tarefas, configuracaoDaRedacao);
    private final PublicadorDeProgresso publicador = new PublicadorDeProgresso(atendimentos, arquivos, evento -> { }, fila, redacao);
    private final List<CoordenadorDaFila> coordenadores = new ArrayList<>();
    private final ConversorDeMentira conversor = new ConversorDeMentira();
    private final QuadroDeMentira extrator = new QuadroDeMentira();
    private CofreAesGcm cofre;
    private Usuario maria;

    @BeforeEach
    void preparar() throws IOException {
        PostgresDeTeste.jdbc().sql("DELETE FROM atendimento.tarefa").update();
        cofre = new CofreAesGcm(new ChaveMestraProperties(raiz.resolve("chave-mestra")));
        maria = usuario("maria");
        servidor.arquivo(PdfDigisacDeTeste.caminhoDoAnexo("1760011620000.jpeg"), jpeg())
                .arquivo(PdfDigisacDeTeste.caminhoDoAnexo("1760011680000.oga"), OGG)
                .arquivo(PdfDigisacDeTeste.CAMINHO_DO_DOCUMENTO, pdf("Relatorio de vendas: total 1.234,56"))
                .arquivo(PdfDigisacDeTeste.caminhoDoVideo("gravacao-tela.mp4"), MP4);
    }

    @AfterEach
    void parar() {
        coordenadores.forEach(CoordenadorDaFila::parar);
        servidor.close();
    }

    // --------------------------------------------------------------------------------------------- montagem

    private static Usuario usuario(String login) {
        return new Usuario(PostgresDeTeste.novoUsuario(true), login, login, "hash", true, Set.of(Perfil.ROLE_USER), null,
                null, false);
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage imagem = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        var g = imagem.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "jpeg", saida);
        return saida.toByteArray();
    }

    private static byte[] pdf(String texto) throws IOException {
        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage();
            documento.addPage(pagina);
            try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
                conteudo.beginText();
                conteudo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                conteudo.newLineAtOffset(72, 700);
                conteudo.showText(texto);
                conteudo.endText();
            }
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            documento.save(saida);
            return saida.toByteArray();
        }
    }

    private Clock relogio() {
        return Clock.fixed(AGORA, FUSO);
    }

    private ProcessarAtendimentos processamento() {
        return new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio(), 2, fila, redacao);
    }

    private MontarLinhaDoTempo montar() {
        return new MontarLinhaDoTempo(atendimentos, analises, json, configuracaoDaRedacao, relogio());
    }

    private RedigirSaida redigir() {
        QuadrosDoVideo quadros = new QuadrosDoVideo(new PastaEmDisco(raiz), new ExtratorQuadrosFfmpeg(List.of(), Duration.ofSeconds(30)));
        return new RedigirSaida(saidas, atendimentos, new ChamadasAoGemini(chaves, cofre, json, relogio()), modelo,
                new PastaEmDisco(raiz), configuracaoDaRedacao, json, relogio(), analises, quadros);
    }

    private PeloAppDoGemini app() {
        return new PeloAppDoGemini(atendimentos, saidas, montar(), redigir(), new ChamadasAoGemini(chaves, cofre, json, relogio()),
                new PastaEmDisco(raiz), new JuntadorPdfBox(), conversor, extrator, json, RepositoriosDeTeste.transacao(), relogio());
    }

    private GerenciarSaidas gerenciarSaidas() {
        return new GerenciarSaidas(atendimentos, saidas, new PastaEmDisco(raiz), configuracaoDaRedacao, json);
    }

    private void iniciarFila() {
        QuadrosDoVideo quadros = new QuadrosDoVideo(new PastaEmDisco(raiz), new ExtratorQuadrosFfmpeg(List.of(), Duration.ofSeconds(30)));
        BaixarAnexo baixar = new BaixarAnexo(arquivos, new PastaEmDisco(raiz),
                new BaixadorHttp(new PoliticaDeRede(List.of("127.0.0.1"), false, true, null), Duration.ofSeconds(2),
                        Duration.ofSeconds(1)),
                publicador, relogio(), DataSize.ofMegabytes(10), DataSize.ofKilobytes(1), 4);
        var analisar = ParaOsTestes.analisar(analises, chaves, cofre, modelo, new PastaEmDisco(raiz), configuracao, relogio(),
                quadros);
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(baixar, analisar, montar(), redigir()), processamento(),
                relogio(), 8, 4, Duration.ofMinutes(15), Duration.ofMillis(100), Duration.ofMillis(1), Duration.ofMillis(5), true);
        coordenadores.add(c);
        c.iniciar();
    }

    private UUID criar() {
        GerenciarAtendimentos gerenciar = new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador,
                new PastaEmDisco(raiz), RepositoriosDeTeste.transacao(), relogio(), DataSize.ofMegabytes(50), 30, 180);
        byte[] pdf = PdfDigisacDeTeste.exemplo("20261009T120000Z", servidor.base()).bytes();
        return gerenciar.receberPdf(maria, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
    }

    private SituacaoDoAtendimento situacao(UUID id) {
        return atendimentos.estado(id).orElseThrow().situacao();
    }

    private void esperarAte(UUID id, SituacaoDoAtendimento esperada) {
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(() -> situacao(id) == esperada);
    }

    /** Baixado e sem análise: sem a chave, as análises falham e os arquivos ficam no servidor. */
    private UUID baixadoSemAnalise() {
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id);
        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        return id;
    }

    private EstadoDaSaida resumo(UUID id) {
        return redacao.estados(id).stream().filter(s -> s.tipo().equals("resumo")).findFirst().orElseThrow();
    }

    // --------------------------------------------------------------------------------------------- testes

    @Test
    void oPedidoJuntaAsImagensEOsDocumentosNumPdfEOsAudiosNumArquivoSo() throws IOException {
        UUID id = baixadoSemAnalise();

        PeloAppDoGemini.PedidoView pedido = app().pedido(maria, id, TipoDeSaida.RESUMO);

        assertThat(pedido.arquivos()).extracting(PeloAppDoGemini.PedidoView.Arquivo::nome)
                .containsExactly("Anexos.pdf", "Audios.wav", "A4_gravacao-tela.mp4");
        assertThat(pedido.arquivos().get(0).conteudo()).contains("A1").contains("A3");
        assertThat(pedido.arquivos().get(1).conteudo()).contains("A2 de 00:00 a 00:02");
        assertThat(pedido.pedido())
                .contains("Use só o que está no material").contains("Escreva o resumo deste atendimento")
                .contains("Como responder, aqui no app do Gemini").contains("- Anexos.pdf: ")
                .contains("```json\n{").contains("\"blocos\"")
                .contains("[A1]").contains("(o conteúdo está no arquivo Anexos.pdf, página 1)")
                .contains("(o conteúdo está no arquivo Anexos.pdf, a partir da página 2)")
                .contains("(o conteúdo está no arquivo Audios.wav, de 00:00 a 00:02)")
                .contains("(o conteúdo está no arquivo A4_gravacao-tela.mp4)")
                .contains("como A4@00:15").contains("- A1: ")
                .doesNotContain("{{").doesNotContain("4815162342");
        assertThat(pedido.avisos()).isEmpty();

        List<PeloAppDoGemini.ArquivoDoPedido> paraBaixar = app().arquivos(maria, id);
        assertThat(paraBaixar).extracting(PeloAppDoGemini.ArquivoDoPedido::nome)
                .containsExactly("Anexos.pdf", "Audios.wav", "A4_gravacao-tela.mp4");
        try (PDDocument anexos = Loader.loadPDF(paraBaixar.get(0).local().toFile())) {
            assertThat(anexos.getNumberOfPages()).as("a imagem, a capa do documento e a página dele").isEqualTo(3);
            String texto = new PDFTextStripper().getText(anexos);
            assertThat(texto).contains("A1 · imagem do cliente").contains("1760011620000.jpeg")
                    .contains("(1 página a seguir)").contains("Relatorio de vendas: total 1.234,56");
        }
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        PeloAppDoGemini.escreverZip(paraBaixar, zip);
        List<String> noZip = new ArrayList<>();
        try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(zip.toByteArray()))) {
            for (ZipEntry e = entrada.getNextEntry(); e != null; e = entrada.getNextEntry()) {
                noZip.add(e.getName());
            }
        }
        assertThat(noZip).containsExactly("Anexos.pdf", "Audios.wav", "A4_gravacao-tela.mp4");
        assertThat(app().arquivo(maria, id, "A4_gravacao-tela.mp4").local()).isRegularFile();
        assertThatThrownBy(() -> app().arquivo(maria, id, "../conversa.pdf")).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void semFfmpegOsAudiosVaoUmPorUmComAExtensaoQueOAppConhece() {
        conversor.disponivel = false;
        extrator.disponivel = false;
        UUID id = baixadoSemAnalise();

        PeloAppDoGemini.PedidoView pedido = app().pedido(maria, id, TipoDeSaida.DEV_ERRO);

        assertThat(pedido.arquivos()).extracting(PeloAppDoGemini.PedidoView.Arquivo::nome)
                .containsExactly("Anexos.pdf", "A2_1760011680000.ogg", "A4_gravacao-tela.mp4");
        assertThat(pedido.avisos()).anyMatch(a -> a.contains("ffmpeg não está instalado"));
        assertThat(pedido.pedido()).contains("Escreva o chamado de erro").doesNotContain("Quadros dos vídeos");
    }

    @Test
    void aRespostaColadaViraOTextoComAsImagensEOQuadroDoVideo() {
        UUID id = baixadoSemAnalise();
        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.RESUMO, "{}"))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "PEDIDO_NAO_MONTADO");
        app().pedido(maria, id, TipoDeSaida.RESUMO);
        String colado = """
                Aqui está o resumo:
                ```json
                {"assunto":"Erro ao emitir a nota fiscal",
                 "blocos":[
                   {"tipo":"paragrafo","texto":"O cliente relatou o erro ao emitir a nota.","fontes":["M1","A1"],
                    "imagens":[{"id":"A1","legenda":"tela com o erro"}]},
                   {"tipo":"passo","texto":"Reproduzi no vídeo enviado.","fontes":["A4"],
                    "imagens":[{"id":"A4@00:03","legenda":"o erro na tela"}]}],
                 "base":{"cliente":"Loja Exemplo","modulo":"Vendas","tela":"Pedidos","codigoTela":"VEN010",
                         "sintoma":"erro ao emitir","mensagemErro":"não identificado","causa":"não identificado",
                         "resolucao":"orientado"},
                 "telaFuncionalidade":"Pedidos (VEN010)"}
                ```
                Se quiser, posso ajustar.""";

        UUID saida = app().usarResposta(maria, id, TipoDeSaida.RESUMO, colado);

        SaidaView view = gerenciarSaidas().detalhe(maria, id, saida);
        assertThat(view.situacao()).isEqualTo("pronta");
        assertThat(view.modelo()).isEqualTo(PeloAppDoGemini.MODELO_DO_APP);
        assertThat(view.assunto()).endsWith(" : Erro ao emitir a nota fiscal");
        assertThat(view.html()).contains("<strong>Imagem 1</strong>: tela com o erro")
                .contains("<strong>Imagem 2</strong>: o erro na tela");
        assertThat(view.imagens()).hasSize(2).allMatch(SaidaView.Imagem::disponivel);
        assertThat(extrator.pedidos).containsExactly(3000L);
        assertThat(view.avisos()).noneMatch(a -> a.contains("Escrito sem o conteúdo"));
        assertThat(resumo(id).versao()).as("não havia resumo: é a primeira versão").isEqualTo(1);
        assertThat(modelo.geracoes()).as("nenhuma chamada à API").isEmpty();
    }

    @Test
    void aRespostaPreencheOTextoQueEsperavaNoAtendimentoPausadoPelaCota() {
        chaves.salvar(new ChaveGemini(maria.id(), cofre.cifrar(CHAVE, maria.id()), "wxyz", false, SituacaoDaChave.VALIDA,
                AGORA, AGORA));
        modelo.padrao(p -> {
            throw new FalhaDoModelo(FalhaDoModelo.Tipo.COTA_DIARIA, "cota", null, 429);
        });
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        esperarAte(id, SituacaoDoAtendimento.PAUSADO);
        await().atMost(10, TimeUnit.SECONDS).until(() -> arquivos.doAtendimento(id).stream()
                .allMatch(a -> a.situacao().equals("pronto")));
        app().pedido(maria, id, TipoDeSaida.RESUMO);

        app().usarResposta(maria, id, TipoDeSaida.RESUMO, ModeloDeMentira.RESUMO);

        assertThat(resumo(id).versao()).isEqualTo(1);
        assertThat(resumo(id).situacao()).isEqualTo("pronta");
    }

    @Test
    void respostaForaDoFormatoOuDeOutraPessoaNaoGrava() {
        UUID id = baixadoSemAnalise();
        app().pedido(maria, id, TipoDeSaida.DEV_ALTERACAO);

        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, "  "))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_VAZIA");
        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, "Não consegui abrir os arquivos."))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_FORA_DO_FORMATO");
        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, ModeloDeMentira.RESUMO))
                .as("o JSON do resumo não tem as partes do chamado")
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("Responda só com o bloco de código JSON");
        assertThatThrownBy(() -> app().pedido(usuario("joao"), id, TipoDeSaida.RESUMO))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(redacao.estados(id)).noneMatch(s -> s.tipo().equals("dev_alteracao"));

        app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, ModeloDeMentira.DEV_ALTERACAO);
        assertThat(redacao.estados(id)).filteredOn(s -> s.tipo().equals("dev_alteracao")).singleElement()
                .satisfies(s -> assertThat(s.situacao()).isEqualTo("pronta"));
    }

    // --------------------------------------------------------------------------------------------- de mentira

    /** O ffmpeg dos áudios: cada áudio vira 2 s de silêncio; o MP3 não sai (fica o WAV). */
    static final class ConversorDeMentira implements ConversorDeMidia {

        volatile boolean disponivel = true;

        @Override
        public boolean disponivel() {
            return disponivel;
        }

        @Override
        public void paraWav(Path origem, Path destino) {
            try {
                Files.createDirectories(destino.getParent());
                ByteArrayOutputStream wav = new ByteArrayOutputStream();
                int dados = 2 * 32_000;
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dados).put("WAVE".getBytes(StandardCharsets.US_ASCII))
                        .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                        .putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16)
                        .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dados);
                wav.write(b.array());
                wav.write(new byte[dados]);
                Files.write(destino, wav.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean paraMp3(Path wav, Path destino) {
            return false;
        }

        @Override
        public Optional<Long> duracaoMs(Path midia) {
            return Optional.of(2000L);
        }
    }

    /** O ffmpeg dos quadros: um PNG pequeno para cada instante pedido. */
    static final class QuadroDeMentira implements ExtratorQuadros {

        volatile boolean disponivel = true;
        final List<Long> pedidos = new ArrayList<>();

        @Override
        public Situacao situacao() {
            return disponivel ? new Situacao(true, "teste", "ffmpeg") : Situacao.AUSENTE;
        }

        @Override
        public void extrair(Path video, long instanteMs, Path destino) {
            pedidos.add(instanteMs);
            try {
                Files.createDirectories(destino.getParent());
                ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", destino.toFile());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
