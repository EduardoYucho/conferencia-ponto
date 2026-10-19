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

    private static final String PDF = "Atendimento_20261009000001.pdf";

    /** O texto de cada página do PDF (a primeira é a 1). */
    private static List<String> paginas(Path pdf) throws IOException {
        List<String> paginas = new ArrayList<>();
        try (PDDocument documento = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper leitor = new PDFTextStripper();
            for (int i = 1; i <= documento.getNumberOfPages(); i++) {
                leitor.setStartPage(i);
                leitor.setEndPage(i);
                paginas.add(leitor.getText(documento));
            }
        }
        return paginas;
    }

    private static int paginaCom(List<String> paginas, String texto) {
        for (int i = 0; i < paginas.size(); i++) {
            if (paginas.get(i).contains(texto)) {
                return i + 1;
            }
        }
        return 0;
    }

    @Test
    void oPacoteTemAConversaEOsAnexosNumPdfOsAudiosNumArquivoEOsVideosAParte() throws IOException {
        UUID id = baixadoSemAnalise();

        PeloAppDoGemini.PacoteView pacote = app().pacote(maria, id);

        assertThat(pacote.arquivos()).extracting(PeloAppDoGemini.PacoteView.Arquivo::nome)
                .containsExactly(PDF, "Audios.wav", "A4_gravacao-tela.mp4");
        assertThat(pacote.arquivos().get(0).conteudo()).startsWith("a conversa e, depois dela").contains("A1, A3");
        assertThat(pacote.arquivos().get(1).conteudo()).contains("A2 de 00:00 a 00:02");
        assertThat(pacote.avisos()).isEmpty();
        assertThat(pacote.versaoDaHabilidade()).isEqualTo(2);

        List<String> paginas = paginas(app().arquivo(maria, id, PDF).local());
        String conversa = String.join("\n", paginas.subList(0, paginaCom(paginas, "A1 · imagem do cliente") - 1));
        int imagem = paginaCom(paginas, "A1 · imagem do cliente");
        int documento = paginaCom(paginas, "(1 página a seguir)");
        assertThat(imagem).as("a imagem vem depois da conversa").isGreaterThan(1);
        assertThat(documento).isEqualTo(imagem + 1);
        assertThat(paginas).hasSize(documento + 1);
        assertThat(paginas.get(documento)).contains("Relatorio de vendas: total 1.234,56");
        assertThat(conversa).contains("PACOTE DO ATENDIMENTO PARA O GEMINI").contains("Contato: Loja Exemplo - Maria")
                .contains("Digisac: Loja Exemplo - Maria :").contains("[A1]").contains("[M7]")
                .contains("(o conteúdo está neste PDF, na página " + imagem + ")")
                .contains("(o conteúdo está neste PDF, a partir da página " + documento + ")")
                .contains("(o conteúdo está no arquivo Audios.wav, de 00:00 a 00:02)")
                .contains("(o conteúdo está no arquivo A4_gravacao-tela.mp4)")
                .contains("Imagens que o texto pode mostrar").contains("- A1: ")
                .contains("[Imagem A4@00:15:")
                .doesNotContain("4815162342").doesNotContain("\"imagens\"");

        assertThat(app().arquivo(maria, id, "Audios.wav").local()).isRegularFile();
        assertThat(app().arquivo(maria, id, "A4_gravacao-tela.mp4").local()).isRegularFile();
        assertThatThrownBy(() -> app().arquivo(maria, id, "../conversa.pdf")).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void aFotoDaTelaDoAcessoRemotoNaoVaiNoPacote() throws IOException {
        chaves.salvar(new ChaveGemini(maria.id(), cofre.cifrar(CHAVE, maria.id()), "wxyz", false, SituacaoDaChave.VALIDA,
                AGORA, AGORA));
        modelo.padrao(p -> p.esquemaJson().contains("\"regioes\"") ? """
                {"descricao":"Janela do AnyDesk com o endereço desta estação","tela":{"nome":"AnyDesk"},"campos":[],
                 "mensagens":[],"textoLegivel":true,"regioes":[]}""" : ModeloDeMentira.respostaPadrao(p));
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id);
        await().atMost(20, TimeUnit.SECONDS).until(() -> arquivos.doAtendimento(id).stream()
                .allMatch(a -> a.situacao().equals("pronto")) && situacao(id) != SituacaoDoAtendimento.PROCESSANDO);

        PeloAppDoGemini.PacoteView pacote = app().pacote(maria, id);

        assertThat(pacote.avisos()).anyMatch(a -> a.startsWith("A1 é a tela do programa de acesso remoto"));
        assertThat(pacote.arquivos()).extracting(PeloAppDoGemini.PacoteView.Arquivo::nome).doesNotContain("A1_1760011620000.jpeg");
        assertThat(pacote.arquivos().get(0).conteudo()).doesNotContain("A1");
        String pdf = String.join("\n", paginas(app().arquivo(maria, id, PDF).local()));
        assertThat(pdf).contains("(o conteúdo está fora do pacote: é a tela do programa de acesso remoto")
                .doesNotContain("A1 · imagem do cliente").doesNotContain("- A1: ");
    }

    @Test
    void semFfmpegOsAudiosVaoUmPorUmComAExtensaoQueOAppConhece() throws IOException {
        conversor.disponivel = false;
        extrator.disponivel = false;
        UUID id = baixadoSemAnalise();

        PeloAppDoGemini.PacoteView pacote = app().pacote(maria, id);

        assertThat(pacote.arquivos()).extracting(PeloAppDoGemini.PacoteView.Arquivo::nome)
                .containsExactly(PDF, "A2_1760011680000.ogg", "A4_gravacao-tela.mp4");
        assertThat(pacote.avisos()).anyMatch(a -> a.contains("ffmpeg não está instalado"));
        String conversa = String.join("\n", paginas(app().arquivo(maria, id, PDF).local()));
        assertThat(conversa).contains("(o conteúdo está no arquivo A2_1760011680000.ogg)")
                .doesNotContain("Momentos dos vídeos");
    }

    @Test
    void oTextoDoGeminiColadoViraOTextoComAsImagensEOMomentoDoVideo() {
        UUID id = baixadoSemAnalise();
        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.RESUMO, "Assunto: x"))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "PACOTE_NAO_MONTADO");
        app().pacote(maria, id);
        String colado = """
                **Assunto:** Digisac: Loja Exemplo - Maria : Erro ao emitir a nota fiscal

                A cliente Maria relatou que não conseguia emitir a nota fiscal: aparecia um erro na tela de vendas.
                [Imagem A1: tela de vendas com o erro]

                O que foi feito:

                1. Acessei o computador da cliente por acesso remoto.
                2. Reproduzi o erro e gravei a tela.
                [Imagem A4@00:03: o erro na tela]
                3. Corrigi o cadastro e a nota foi emitida.

                A nota foi emitida e o atendimento foi encerrado.
                """;

        UUID saida = app().usarResposta(maria, id, TipoDeSaida.RESUMO, colado);

        SaidaView view = gerenciarSaidas().detalhe(maria, id, saida);
        assertThat(view.situacao()).isEqualTo("pronta");
        assertThat(view.modelo()).isEqualTo(PeloAppDoGemini.MODELO_DA_HABILIDADE);
        assertThat(view.assunto()).isEqualTo("Digisac: Loja Exemplo - Maria : Erro ao emitir a nota fiscal");
        assertThat(view.html()).contains("aparecia um erro na tela de vendas.")
                .contains("<strong>Imagem 1</strong>: tela de vendas com o erro")
                .doesNotContain("<li").doesNotContain("<ol").contains("<p data-fontes=\"A4\">Reproduzi o erro e gravei a tela.</p>")
                .contains("<strong>Imagem 2</strong>: o erro na tela")
                .contains("A nota foi emitida e o atendimento foi encerrado.")
                .doesNotContain("[Imagem").doesNotContain("**");
        assertThat(view.imagens()).hasSize(2).allMatch(SaidaView.Imagem::disponivel);
        assertThat(extrator.pedidos).containsExactly(3000L);
        assertThat(view.avisos()).as("o Gem não traz as fontes: não é aviso").noneMatch(a -> a.contains("sem fonte"))
                .noneMatch(a -> a.contains("Escrito sem o conteúdo"));
        assertThat(resumo(id).versao()).as("não havia resumo: é a primeira versão").isEqualTo(1);
        assertThat(modelo.geracoes()).as("nenhuma chamada à API").isEmpty();
    }

    @Test
    void oChamadoDeErroDoGemVemNasPartesComOsDadosParaABase() {
        UUID id = baixadoSemAnalise();
        app().pacote(maria, id);
        String colado = """
                Assunto: Digisac: Loja Exemplo - Maria : Erro ao emitir nota na tela de vendas
                Tela/Funcionalidade: Pedidos (VEN010)

                ## Relato do problema
                Ao emitir a nota fiscal na tela de vendas, aparece um erro e a nota não é emitida.

                ## Passos para reproduzir
                1. Abrir a tela Pedidos (VEN010).
                2. Clicar em Emitir NF-e.

                ## Resultado obtido
                O sistema mostra a mensagem "Erro ao emitir".
                [Imagem A1: a mensagem de erro]

                ## Causa
                Causa não identificada.

                Cliente: Loja Exemplo
                Módulo: Vendas
                Tela: Pedidos
                Código da tela: VEN010
                Sintoma: a nota não é emitida
                Mensagem de erro: "Erro ao emitir"
                Causa: não identificado
                Resolução: enviado ao desenvolvimento
                """;

        UUID saida = app().usarResposta(maria, id, TipoDeSaida.DEV_ERRO, colado);

        SaidaView view = gerenciarSaidas().detalhe(maria, id, saida);
        assertThat(view.telaFuncionalidade()).isEqualTo("Pedidos (VEN010)");
        assertThat(view.html()).contains("Relato do problema").contains("Passos para reproduzir")
                .contains("Clicar em Emitir NF-e.").contains("Resultado obtido").contains("<strong>Imagem 1</strong>: a mensagem de erro")
                .contains("Causa não identificada.").doesNotContain("Cliente: Loja Exemplo").doesNotContain("##");
        assertThat(view.base()).containsEntry("cliente", "Loja Exemplo").containsEntry("codigoTela", "VEN010")
                .containsEntry("resolucao", "enviado ao desenvolvimento");
        assertThat(view.registro()).isNotNull();
        assertThat(view.registro().cliente()).isEqualTo("Loja Exemplo");
        assertThat(view.registro().telaCodigo()).isEqualTo("VEN010");
    }

    @Test
    void aRespostaEmJsonContinuaValendo() {
        UUID id = baixadoSemAnalise();
        app().pacote(maria, id);
        String colado = """
                ```json
                {"assunto":"Erro ao emitir a nota fiscal",
                 "blocos":[{"tipo":"paragrafo","texto":"O cliente relatou o erro.","fontes":["M7"],
                            "imagens":[{"id":"A1","legenda":"tela com o erro"}]}],
                 "base":{"cliente":"Loja Exemplo","modulo":"Vendas","tela":"Pedidos","codigoTela":"VEN010",
                         "sintoma":"erro ao emitir","mensagemErro":"não identificado","causa":"não identificado",
                         "resolucao":"orientado"},
                 "telaFuncionalidade":"Pedidos (VEN010)"}
                ```""";

        UUID saida = app().usarResposta(maria, id, TipoDeSaida.RESUMO, colado);

        SaidaView view = gerenciarSaidas().detalhe(maria, id, saida);
        assertThat(view.modelo()).isEqualTo(PeloAppDoGemini.MODELO_DO_APP);
        assertThat(view.html()).contains("data-fontes=\"M7").contains("<strong>Imagem 1</strong>: tela com o erro");
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
        app().pacote(maria, id);

        app().usarResposta(maria, id, TipoDeSaida.RESUMO, "Assunto: Erro na nota\n\nA cliente relatou o erro na nota.");

        assertThat(resumo(id).versao()).isEqualTo(1);
        assertThat(resumo(id).situacao()).isEqualTo("pronta");
    }

    @Test
    void respostaVaziaSemTextoOuDeOutraPessoaNaoGrava() {
        UUID id = baixadoSemAnalise();
        app().pacote(maria, id);

        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, "  "))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_VAZIA");
        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, "Assunto: só o assunto"))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_SEM_TEXTO");
        assertThatThrownBy(() -> app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, ModeloDeMentira.RESUMO))
                .as("o JSON do resumo não tem as partes do chamado")
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_FORA_DO_FORMATO");
        assertThatThrownBy(() -> app().pacote(usuario("joao"), id)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> app().usarResposta(usuario("joao"), id, TipoDeSaida.RESUMO, "Assunto: x\n\ntexto"))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(redacao.estados(id)).noneMatch(s -> s.tipo().equals("dev_alteracao"));

        app().usarResposta(maria, id, TipoDeSaida.DEV_ALTERACAO, """
                Assunto: Novo relatório de vendas por vendedor
                Tela/Funcionalidade: Relatórios de Vendas

                Solicitação de Melhoria

                ## Descrição resumida
                Criar o relatório Vendas por Vendedor.

                ## Pontos a confirmar com o cliente
                1. Quais colunas o relatório deve ter?
                """);
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
