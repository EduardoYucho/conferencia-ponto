package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ServidorDeAnexos;
import br.com.conferenciaponto.modulos.atendimento.application.analise.AnaliseView;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ConsultarAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.analise.FilaDeAnalises;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ParaOsTestes;
import br.com.conferenciaponto.modulos.atendimento.application.analise.PanoramaDasAnalises;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.AtendimentoView;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.fila.CoordenadorDaFila;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.SituacaoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.BaixadorHttp;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.PoliticaDeRede;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Do PDF à análise de cada arquivo: os anexos baixados entram na fila da análise, a cota do dia pausa só a IA, a
 * falta de chave avisa e as análises guardadas não são refeitas (banco, pasta e fila de verdade; Gemini de mentira).
 */
class ProcessamentoDasAnalisesTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final String CHAVE = "AQ.Ab8RN6Kc-chave_de-teste.0123456789wxyz";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 'J', 'F', 'I', 'F', 0, 1, 2, 3};
    private static final byte[] OGG = "OggS\0\2\0\0\0\0\0\0opus".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PDF = "%PDF-1.7\nrelatorio".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MP4 = "\0\0\0\u0018ftypisom\0\0\0\0video".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path raiz;

    private final ServidorDeAnexos servidor = new ServidorDeAnexos();
    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();
    private final Arquivos arquivos = RepositoriosDeTeste.arquivos();
    private final Tarefas tarefas = RepositoriosDeTeste.tarefas();
    private final Analises analises = RepositoriosDeTeste.analises();
    private final ChavesGemini chaves = RepositoriosDeTeste.chaves();
    private final ModeloDeMentira modelo = new ModeloDeMentira();
    private final ConfiguracaoDaAnalise configuracao = new ConfiguracaoDaAnalise("gemini-3.8-flash", "gemini-3.8-flash", 3,
            15 * 1024 * 1024, 32768, null, null);
    private final FilaDeAnalises fila = new FilaDeAnalises(analises, tarefas, configuracao, chaves);
    private final List<ProgressoAtualizado> eventos = new CopyOnWriteArrayList<>();
    private final PublicadorDeProgresso publicador = new PublicadorDeProgresso(atendimentos, arquivos,
            evento -> eventos.add((ProgressoAtualizado) evento), fila);
    private final List<CoordenadorDaFila> coordenadores = new ArrayList<>();
    private CofreAesGcm cofre;
    private Usuario maria;

    @BeforeEach
    void preparar() {
        PostgresDeTeste.jdbc().sql("DELETE FROM atendimento.tarefa").update();
        cofre = new CofreAesGcm(new ChaveMestraProperties(raiz.resolve("chave-mestra")));
        maria = new Usuario(PostgresDeTeste.novoUsuario(true), "maria", "maria", "hash", true, Set.of(Perfil.ROLE_USER),
                null, null, false);
        servidor.arquivo(PdfDigisacDeTeste.caminhoDoAnexo("1760011620000.jpeg"), JPEG)
                .arquivo(PdfDigisacDeTeste.caminhoDoAnexo("1760011680000.oga"), OGG)
                .arquivo(PdfDigisacDeTeste.CAMINHO_DO_DOCUMENTO, PDF)
                .arquivo(PdfDigisacDeTeste.caminhoDoVideo("gravacao-tela.mp4"), MP4);
    }

    @AfterEach
    void parar() {
        coordenadores.forEach(CoordenadorDaFila::parar);
        servidor.close();
    }

    // --------------------------------------------------------------------------------------------- montagem

    private Clock relogio() {
        return Clock.fixed(AGORA, FUSO);
    }

    private ProcessarAtendimentos processamento() {
        return new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio(), 2, fila);
    }

    private void iniciarFila() {
        BaixarAnexo baixar = new BaixarAnexo(arquivos, new PastaEmDisco(raiz),
                new BaixadorHttp(new PoliticaDeRede(List.of("127.0.0.1"), false, true, null), Duration.ofSeconds(2),
                        Duration.ofSeconds(1)),
                publicador, relogio(), DataSize.ofMegabytes(10), DataSize.ofKilobytes(1), 4);
        var analisar = ParaOsTestes.analisar(analises, chaves, cofre, modelo, new PastaEmDisco(raiz), configuracao, relogio());
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(baixar, analisar), processamento(), relogio(), 8, 4,
                Duration.ofMinutes(15), Duration.ofMillis(100), Duration.ofMillis(1), Duration.ofMillis(5), true);
        coordenadores.add(c);
        c.iniciar();
    }

    private UUID criar() {
        GerenciarAtendimentos gerenciar = new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador,
                new PastaEmDisco(raiz), RepositoriosDeTeste.transacao(), relogio(), DataSize.ofMegabytes(50), 30, 180);
        byte[] pdf = PdfDigisacDeTeste.exemplo("20261009T120000Z", servidor.base()).bytes();
        return gerenciar.receberPdf(maria, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
    }

    private GerenciarAtendimentos gerenciar() {
        return new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador, new PastaEmDisco(raiz),
                RepositoriosDeTeste.transacao(), relogio(), DataSize.ofMegabytes(50), 30, 180);
    }

    private void cadastrarChave() {
        chaves.salvar(new ChaveGemini(maria.id(), cofre.cifrar(CHAVE, maria.id()), "wxyz", false, SituacaoDaChave.VALIDA,
                AGORA, AGORA));
    }

    private SituacaoDoAtendimento situacao(UUID id) {
        return atendimentos.estado(id).orElseThrow().situacao();
    }

    private void esperarAte(UUID id, SituacaoDoAtendimento esperada) {
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(() -> situacao(id) == esperada);
    }

    private ArquivoDoAtendimento anexo(UUID atendimento, String nome) {
        return arquivos.doAtendimento(atendimento).stream().filter(a -> a.nome().equals(nome)).findFirst().orElseThrow();
    }

    private SituacaoDaAnalise analise(UUID atendimento, String nome) {
        var estado = fila.panorama(atendimento).de(anexo(atendimento, nome).id());
        return estado == null ? null : estado.situacao();
    }

    // --------------------------------------------------------------------------------------------- testes

    @Test
    void cadaAnexoBaixadoEAnalisadoEOAtendimentoSoFicaProntoNoFim() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(analise(id, "1760011620000.jpeg")).isEqualTo(SituacaoDaAnalise.PRONTA);
        assertThat(analise(id, "1760011680000.oga")).isEqualTo(SituacaoDaAnalise.PRONTA);
        assertThat(analise(id, "Relatório de vendas.pdf")).isEqualTo(SituacaoDaAnalise.PRONTA);
        assertThat(analise(id, "gravacao-tela.mp4")).isEqualTo(SituacaoDaAnalise.PRONTA);
        assertThat(modelo.geracoes()).hasSize(4);
        assertThat(modelo.enviados()).as("áudio, documento e vídeo pela Files API").hasSize(3);
        assertThat(modelo.apagados()).hasSize(3);
        assertThat(modelo.geracoes()).filteredOn(g -> g.pedido().esquemaJson().contains("instanteDoQuadro")).singleElement()
                .satisfies(g -> assertThat(g.pedido().midia().quadrosPorSegundo()).isEqualTo(1.0));
        PanoramaDasAnalises panorama = fila.panorama(id);
        assertThat(panorama.tokensEntrada()).isEqualTo(4000);
        assertThat(panorama.tokensSaida()).isEqualTo(800);
        assertThat(panorama.semChaveGemini()).isFalse();
        ProgressoView fim = processamento().progresso(maria, id);
        assertThat(fim.percentual()).isEqualTo(100);
        assertThat(fim.arquivos()).filteredOn(a -> "pronta".equals(a.analise())).hasSize(4);
        assertThat(eventos).anySatisfy(e -> assertThat(e.progresso().arquivos()).anyMatch(a -> "analisando".equals(a.analise())));

        AtendimentoView detalhe = gerenciar().detalhe(maria, id);
        assertThat(detalhe.tokensEntrada()).isEqualTo(4000);
        assertThat(detalhe.anexos()).filteredOn(a -> "pronta".equals(a.analise())).hasSize(4);
        assertThat(detalhe.percentual()).isEqualTo(100);
    }

    @Test
    void processarDeNovoNaoRefazOQueJaFoiAnalisado() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id);
        esperarAte(id, SituacaoDoAtendimento.PRONTO);

        processamento().processar(maria, id);

        await().during(Duration.ofMillis(400)).atMost(2, TimeUnit.SECONDS).until(() -> situacao(id) == SituacaoDoAtendimento.PRONTO);
        assertThat(modelo.geracoes()).hasSize(4);
    }

    @Test
    void cotaDoDiaPausaSoAsAnalisesOsDownloadsSeguemERetomarContinuaDeOndeParou() {
        cadastrarChave();
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.COTA_DIARIA, "acabou"));
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.PAUSADO);
        await().atMost(20, TimeUnit.SECONDS).until(() -> arquivos.doAtendimento(id).stream().allMatch(a -> a.situacao().equals("pronto")));
        assertThat(atendimentos.estado(id).orElseThrow().motivoPausa()).contains("cota diária").contains("Retomar");
        await().during(Duration.ofMillis(300)).atMost(3, TimeUnit.SECONDS)
                .until(() -> situacao(id) == SituacaoDoAtendimento.PAUSADO);

        processamento().retomar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(fila.panorama(id).resolvidas()).isEqualTo(4);
        assertThat(modelo.geracoes()).as("a que pausou e as quatro").hasSize(5);
        assertThat(chaves.buscar(maria.id()).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.VALIDA);
    }

    @Test
    void semChaveAsAnalisesFalhamComOAvisoEDepoisDeCadastrarProcessarResolve() {
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        PanoramaDasAnalises panorama = fila.panorama(id);
        assertThat(panorama.semChaveGemini()).isTrue();
        assertThat(panorama.estados().values()).hasSize(4)
                .allSatisfy(e -> assertThat(e.codigo()).isEqualTo("SEM_CHAVE_GEMINI"));
        assertThat(processamento().progresso(maria, id).semChaveGemini()).isTrue();
        assertThat(modelo.geracoes()).isEmpty();

        cadastrarChave();
        processamento().processar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(fila.panorama(id).semChaveGemini()).isFalse();
        assertThat(modelo.geracoes()).hasSize(4);
    }

    @Test
    void umaAnaliseQueFalhaNaoParaAsOutrasEPodeSerTentadaDeNovo() {
        cadastrarChave();
        modelo.padrao(p -> p.esquemaJson().contains("\"trechos\"") ? "não é json" : ModeloDeMentira.respostaPadrao(p));
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        UUID audio = anexo(id, "1760011680000.oga").id();
        var estado = fila.panorama(id).de(audio);
        assertThat(estado.situacao()).isEqualTo(SituacaoDaAnalise.FALHOU);
        assertThat(estado.codigo()).isEqualTo("GEMINI_RESPOSTA_INVALIDA");
        assertThat(analise(id, "1760011620000.jpeg")).isEqualTo(SituacaoDaAnalise.PRONTA);

        modelo.padrao(ModeloDeMentira::respostaPadrao);
        processamento().processar(maria, id);
        await().during(Duration.ofMillis(300)).atMost(2, TimeUnit.SECONDS)
                .until(() -> situacao(id) == SituacaoDoAtendimento.COM_FALHAS); // "Processar" não repete falha do arquivo
        assertThat(processamento().tentarDeNovo(maria, id, audio).situacao()).isEqualTo("processando");

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(fila.panorama(id).de(audio).situacao()).isEqualTo(SituacaoDaAnalise.PRONTA);
        assertThatThrownBy(() -> processamento().tentarDeNovo(maria, id, audio))
                .hasFieldOrPropertyWithValue("codigo", "NADA_A_TENTAR");
    }

    @Test
    void conteudoTrocadoApagaAAnaliseAntigaEArquivoTiradoTambem() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id);
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        UUID jpeg = anexo(id, "1760011620000.jpeg").id();
        UUID pdf = anexo(id, "Relatório de vendas.pdf").id();

        processamento().conteudoTrocado(id, jpeg);
        processamento().arquivoTirado(pdf);

        assertThat(analises.maisRecente(jpeg)).isEmpty();
        assertThat(analises.maisRecente(pdf)).isEmpty();
        assertThat(fila.panorama(id).de(jpeg).situacao()).isEqualTo(SituacaoDaAnalise.PENDENTE);
    }

    @Test
    void aAnaliseAbertaNaTelaSoParaODono() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id);
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        UUID jpeg = anexo(id, "1760011620000.jpeg").id();
        ConsultarAnalise consultar = new ConsultarAnalise(analises, configuracao, new ObjectMapper(),
                ParaOsTestes.semFfmpeg(new PastaEmDisco(raiz)), new PastaEmDisco(raiz));

        AnaliseView view = consultar.doArquivo(maria, id, jpeg);

        assertThat(view.categoria()).isEqualTo("imagem");
        assertThat(view.atual()).isTrue();
        assertThat(view.resultado().at("/tela/codigo").asText()).isEqualTo("VEN010");
        assertThat(view.tokensEntrada()).isEqualTo(1000);
        Usuario joao = new Usuario(PostgresDeTeste.novoUsuario(true), "joao", "joao", "hash", true, Set.of(Perfil.ROLE_USER),
                null, null, false);
        assertThatThrownBy(() -> consultar.doArquivo(joao, id, jpeg)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(view.quadros()).isEmpty();

        // o vídeo, num servidor sem o ffmpeg: os passos com o instante do quadro, sem a imagem e com o aviso
        UUID video = anexo(id, "gravacao-tela.mp4").id();
        AnaliseView doVideo = consultar.doArquivo(maria, id, video);
        assertThat(doVideo.categoria()).isEqualTo("video");
        assertThat(doVideo.resultado().path("passos")).hasSize(3);
        assertThat(doVideo.quadros()).extracting(AnaliseView.Quadro::instante).containsExactly("00:03", "00:12", "00:30");
        assertThat(doVideo.quadros()).noneMatch(AnaliseView.Quadro::disponivel);
        assertThat(doVideo.avisoDosQuadros()).contains("winget install Gyan.FFmpeg");
        assertThatThrownBy(() -> consultar.quadro(maria, id, video, 3_000))
                .hasFieldOrPropertyWithValue("codigo", "QUADRO_NAO_ENCONTRADO");
        assertThatThrownBy(() -> consultar.quadro(joao, id, video, 3_000)).isInstanceOf(RecursoNaoEncontradoException.class);
    }
}
