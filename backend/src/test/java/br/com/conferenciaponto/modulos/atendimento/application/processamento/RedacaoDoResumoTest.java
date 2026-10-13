package br.com.conferenciaponto.modulos.atendimento.application.processamento;

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
import br.com.conferenciaponto.modulos.atendimento.application.redacao.CopiaView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.FilaDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.GerenciarSaidas;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.LinhaDoTempoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ModoDeCopia;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.MontarLinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.RedigirSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidaView;
import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.CamposInformados;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.BaixadorHttp;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.PoliticaDeRede;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.ExtratorQuadrosFfmpeg;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.FfmpegDeMentira;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Do PDF ao resumo: a redação só começa quando os downloads e as análises terminam, usa a linha do tempo como único
 * material, passa pela conferência do servidor e o atendimento só fica pronto no fim (banco, pasta e fila de
 * verdade; Gemini de mentira).
 */
class RedacaoDoResumoTest {

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
    private final List<ProgressoAtualizado> eventos = new CopyOnWriteArrayList<>();
    private final PublicadorDeProgresso publicador = new PublicadorDeProgresso(atendimentos, arquivos,
            evento -> eventos.add((ProgressoAtualizado) evento), fila, redacao);
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

    /** Parado em AGORA; o teste que precisa das novas tentativas (que esperam um pouco) usa um que anda. */
    private Clock relogio = Clock.fixed(AGORA, FUSO);

    private Clock relogio() {
        return relogio;
    }

    private ProcessarAtendimentos processamento() {
        return new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio(), 2, fila, redacao);
    }

    private GerenciarSaidas gerenciarSaidas() {
        return new GerenciarSaidas(atendimentos, saidas, new PastaEmDisco(raiz), configuracaoDaRedacao, json);
    }

    private void iniciarFila() {
        iniciarFila(List.of());
    }

    /** Com o "ffmpeg" pedido (vazio: um servidor sem o ffmpeg). */
    private void iniciarFila(List<String> ffmpeg) {
        QuadrosDoVideo quadros = new QuadrosDoVideo(new PastaEmDisco(raiz), new ExtratorQuadrosFfmpeg(ffmpeg, Duration.ofSeconds(30)));
        BaixarAnexo baixar = new BaixarAnexo(arquivos, new PastaEmDisco(raiz),
                new BaixadorHttp(new PoliticaDeRede(List.of("127.0.0.1"), false, true, null), Duration.ofSeconds(2),
                        Duration.ofSeconds(1)),
                publicador, relogio(), DataSize.ofMegabytes(10), DataSize.ofKilobytes(1), 4);
        var analisar = ParaOsTestes.analisar(analises, chaves, cofre, modelo, new PastaEmDisco(raiz), configuracao, relogio(),
                quadros);
        var montar = new MontarLinhaDoTempo(atendimentos, analises, json, configuracaoDaRedacao, relogio());
        var redigir = new RedigirSaida(saidas, atendimentos, new ChamadasAoGemini(chaves, cofre, json, relogio()), modelo,
                new PastaEmDisco(raiz), configuracaoDaRedacao, json, relogio(), analises, quadros);
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(baixar, analisar, montar, redigir), processamento(),
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

    private GerenciarAtendimentos gerenciar() {
        return new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador, new PastaEmDisco(raiz),
                RepositoriosDeTeste.transacao(), relogio(), DataSize.ofMegabytes(50), 30, 180);
    }

    /** Um atendimento só com a conversa, sem anexos. */
    private UUID criarSemAnexos() {
        GerenciarAtendimentos gerenciar = new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador,
                new PastaEmDisco(raiz), RepositoriosDeTeste.transacao(), relogio(), DataSize.ofMegabytes(50), 30, 180);
        byte[] pdf = PdfDigisacDeTeste.novo()
                .cabecalho(PdfDigisacDeTeste.CONTATO, "20261009000002", "09/10/2026 16:05:00", "09/10/2026 16:42:00", "-")
                .data("09/10/2026")
                .evento("Início do chamado - Pré-Atendimento (Fila) - 09/10/2026 16:05:53")
                .cliente(PdfDigisacDeTeste.CONTATO, "16:05", "Quero marcar uma reunião sobre o sistema")
                .atendente("Atendente", "16:30", "Na terça-feira a equipe conversa com você para marcar")
                .cliente(PdfDigisacDeTeste.CONTATO, "16:40", "Perfeito, obrigada")
                .evento("Fim do chamado - Encerrado - 09/10/2026 16:42:02")
                .bytes();
        return gerenciar.receberPdf(maria, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
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

    private EstadoDaSaida resumo(UUID id) {
        return redacao.estados(id).stream().filter(s -> s.tipo().equals("resumo")).findFirst().orElseThrow();
    }

    private List<PedidoAoModelo> redacoes() {
        return modelo.geracoes().stream().map(ModeloDeMentira.Geracao::pedido)
                .filter(p -> p.esquemaJson().contains("\"blocos\"")).toList();
    }

    // --------------------------------------------------------------------------------------------- testes

    @Test
    void oResumoERedigidoDepoisDasAnalisesConferidoEOAtendimentoSoFicaProntoNoFim() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();

        ProgressoView inicio = processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        assertThat(inicio.saidas()).singleElement().satisfies(s -> assertThat(s.situacao()).isEqualTo("pendente"));

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        EstadoDaSaida estado = resumo(id);
        assertThat(estado.situacao()).isEqualTo("pronta");
        assertThat(estado.versao()).isEqualTo(1);
        assertThat(modelo.geracoes()).as("4 análises e 1 redação").hasSize(5);

        // o material foi a linha do tempo, sem a chave do bot nem os dados de acesso da conversa
        PedidoAoModelo pedido = redacoes().get(0);
        assertThat(pedido.midia()).isNull();
        assertThat(pedido.instrucaoDoSistema()).contains("Use só o que está no material");
        assertThat(pedido.instrucao()).contains("[M1]").contains("[A1]").contains("Tela de pedidos com erro")
                .contains("O sistema travou na emissão da nota").contains("Imagens disponíveis").contains("- A1: tela Pedidos (VEN010)")
                .doesNotContain("4815162342").doesNotContain("4821").doesNotContain("123 456 789")
                .doesNotContain("{{");
        assertThat(modelo.geracoes()).allSatisfy(g -> assertThat(g.chave()).isEqualTo(CHAVE));

        SaidaView saida = gerenciarSaidas().detalhe(maria, id, estado.id());
        assertThat(saida.assunto()).startsWith("Digisac: ").endsWith(" : Erro ao emitir a nota fiscal na tela de vendas")
                .hasSizeLessThanOrEqualTo(150);
        assertThat(saida.html()).contains("<strong>Imagem 1</strong>: tela Pedidos com o erro").contains("<ol>")
                .contains("[omitido]").doesNotContain("123 456 789").doesNotContain("Imagem 2");
        assertThat(saida.imagens()).singleElement().satisfies(i -> {
            assertThat(i.numero()).isEqualTo(1);
            assertThat(i.arquivo()).isEqualTo("Imagem_01.jpeg");
            assertThat(i.disponivel()).isTrue();
            assertThat(i.incluida()).isTrue();
        });
        assertThat(saida.avisos()).anyMatch(a -> a.contains("fonte citada não existe"))
                .anyMatch(a -> a.contains("imagem citada não está entre as do atendimento"))
                .anyMatch(a -> a.contains("[omitido]"));
        assertThat(saida.base()).containsEntry("codigoTela", "VEN010");
        assertThat(saida.telaFuncionalidade()).isEqualTo("Pedidos (VEN010)");
        assertThat(saida.tokensEntrada()).isEqualTo(1000);

        LinhaDoTempoView linha = gerenciarSaidas().linhaDoTempo(maria, id);
        assertThat(linha.itens()).extracting(LinhaDoTempoView.Item::id).contains("M1", "A1", "A2", "A3", "A4");
        assertThat(linha.itens()).filteredOn(i -> i.id().equals("A4")).singleElement()
                .satisfies(i -> assertThat(i.situacao()).isEqualTo("analisado"));
        assertThat(saida.avisos()).as("o vídeo, num servidor sem o ffmpeg")
                .anyMatch(a -> a.contains("Os passos do vídeo A4 ficaram sem quadros") && a.contains("winget install Gyan.FFmpeg"));

        ProgressoView fim = processamento().progresso(maria, id);
        assertThat(fim.tokensEntrada()).as("análises e redação").isEqualTo(5000);
        assertThat(fim.saidas()).singleElement().satisfies(s -> assertThat(s.situacao()).isEqualTo("pronta"));
        assertThat(eventos).anySatisfy(e -> assertThat(e.progresso().saidas())
                .anyMatch(s -> s.situacao().equals("pronta")));
    }

    @Test
    void atendimentoNovoSemAnexosGeraOResumoSoComAConversa() {
        cadastrarChave();
        UUID id = criarSemAnexos();
        iniciarFila();
        assertThat(situacao(id)).isEqualTo(SituacaoDoAtendimento.NOVO);

        ProgressoView inicio = processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        assertThat(inicio.situacao()).isIn("processando", "pronto");

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(resumo(id).situacao()).isEqualTo("pronta");
        assertThat(modelo.geracoes()).as("só a redação").hasSize(1);
        PedidoAoModelo pedido = redacoes().get(0);
        assertThat(pedido.instrucao()).contains("Quero marcar uma reunião").contains("Nenhuma: deixe \"imagens\" vazio");
        SaidaView saida = gerenciarSaidas().detalhe(maria, id, resumo(id).id());
        assertThat(saida.imagens()).as("a imagem citada não existe neste atendimento").isEmpty();
        assertThat(saida.html()).doesNotContain("data-imagem");
    }

    @Test
    void gerarDeNovoCriaAVersaoSeguinteSemAnalisarDeNovo() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();
        assertThatThrownBy(() -> processamento().gerar(maria, id, TipoDeSaida.RESUMO)).as("ainda não processado")
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("Processar");
        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        await().atMost(10, TimeUnit.SECONDS).until(() -> resumo(id).situacao().equals("pronta"));
        int antes = modelo.geracoes().size();

        processamento().gerar(maria, id, TipoDeSaida.RESUMO);
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> resumo(id).versao() == 2 && resumo(id).situacao().equals("pronta"));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);

        assertThat(modelo.geracoes()).as("só a redação").hasSize(antes + 1);
        assertThat(saidas.tokens(id)).containsExactly(2000L, 400L);
    }

    @Test
    void gerarDeNovoEnquantoAVersaoAindaEstaNaFilaAvisa() {
        UUID id = criarSemAnexos();
        atendimentos.mudarSituacao(id, SituacaoDoAtendimento.PRONTO, null, AGORA); // sem a fila rodando

        processamento().gerar(maria, id, TipoDeSaida.DEV_ERRO);

        assertThatThrownBy(() -> processamento().gerar(maria, id, TipoDeSaida.DEV_ERRO))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("já está sendo gerado");
        assertThat(redacao.estados(id)).singleElement().satisfies(s -> {
            assertThat(s.tipo()).isEqualTo("dev_erro");
            assertThat(s.situacao()).isEqualTo("pendente");
        });
    }

    @Test
    void chamadosDeErroEDeAlteracaoComAsPartesOCabecalhoEORodape() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();
        gerenciar().salvarCampos(maria, id, new CamposInformados("01/10/2026", null, "Z:\\Bases Clientes\\Loja", null));

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO, TipoDeSaida.DEV_ERRO, TipoDeSaida.DEV_ALTERACAO));

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(redacao.estados(id)).extracting(EstadoDaSaida::tipo).containsExactly("resumo", "dev_erro", "dev_alteracao");
        assertThat(redacao.estados(id)).allSatisfy(s -> assertThat(s.situacao()).isEqualTo("pronta"));
        EstadoDaSaida erro = redacao.estados(id).get(1);
        SaidaView chamado = gerenciarSaidas().detalhe(maria, id, erro.id());
        assertThat(chamado.html()).startsWith("<p><strong>Caminho da tela</strong></p>")
                .contains("<p><strong>Passos para reproduzir</strong></p>\n<ol>\n<li data-fontes=\"A1\">Abrir a tela Pedidos.</li>")
                .contains("<p><strong>Causa</strong></p>\n<p>Causa não identificada.</p>")
                .contains("Imagem 1").doesNotContain("parte que não existe");
        assertThat(chamado.html().indexOf("Relato do problema")).isLessThan(chamado.html().indexOf("Pedido ao desenvolvimento"));
        assertThat(chamado.telaFuncionalidade()).as("sugerida pela tela lida no print").isEqualTo("Pedidos (VEN010)");
        assertThat(chamado.variantes()).containsExactly("sem_causa_identificada");
        assertThat(chamado.cabecalho()).contains("<strong>Tela/Funcionalidade:</strong> Pedidos (VEN010)")
                .contains("<strong>Versão do cliente:</strong> 01/10/2026")
                .contains("<strong>Testado na última versão disponível:</strong> não informado");
        assertThat(chamado.rodape()).contains("Data de atualização do sistema:</strong> 01/10/2026")
                .contains("Caminho do backup:</strong> Z:\\Bases Clientes\\Loja");
        assertThat(chamado.avisos()).noneMatch(a -> a.contains("sem fonte")).as("a causa do sistema não é trecho sem fonte");

        // os campos mudam e o cabeçalho acompanha, sem gerar de novo
        int geracoes = modelo.geracoes().size();
        gerenciar().salvarCampos(maria, id, new CamposInformados("05/10/2026", true, null, "OTRS 99"));
        SaidaView depois = gerenciarSaidas().salvarTela(maria, id, erro.id(), "Pedidos de Venda (VEN010)");
        assertThat(depois.cabecalho()).contains("05/10/2026").contains("Sim").contains("OTRS 99").contains("Pedidos de Venda (VEN010)");
        assertThat(depois.rodape()).contains("Caminho do backup:</strong> não informado");
        assertThat(modelo.geracoes()).hasSize(geracoes);
        CopiaView copia = gerenciarSaidas().copia(maria, id, erro.id(), ModoDeCopia.SO_TEXTO);
        assertThat(copia.html()).startsWith("<p><strong>Tela/Funcionalidade:</strong> Pedidos de Venda (VEN010)</p>")
                .endsWith("<p><strong>Caminho do backup:</strong> não informado</p>\n").doesNotContain("data-fontes");
        assertThat(copia.texto()).startsWith("Tela/Funcionalidade: Pedidos de Venda (VEN010)").contains("1. Abrir a tela Pedidos.");

        SaidaView alteracao = gerenciarSaidas().detalhe(maria, id, redacao.estados(id).get(2).id());
        assertThat(alteracao.html()).startsWith("<p><strong>Solicitação de Melhoria</strong></p>\n<p><strong>Módulo e telas envolvidas</strong></p>")
                .doesNotContain("Contexto e objetivo").doesNotContain("Causa")
                .contains("<p><strong>Detalhamento do que criar ou mudar</strong></p>\n<ol>\n<li data-fontes=\"M1\">Campo Desconto máximo (%)")
                .endsWith("<p><strong>Pontos a confirmar com o cliente</strong></p>\n<ol>\n<li data-fontes=\"M1\">O limite vale também para o gerente?</li>\n</ol>");
        PedidoAoModelo pedidoDaAlteracao = modelo.geracoes().stream().map(ModeloDeMentira.Geracao::pedido)
                .filter(p -> p.esquemaJson().contains("\"pontosAConfirmar\"")).findFirst().orElseThrow();
        assertThat(pedidoDaAlteracao.instrucao()).contains("\"pontosAConfirmar\"").contains("O detalhe do pedido costuma estar no vídeo");
        assertThat(alteracao.telaFuncionalidade()).isEqualTo("Pedidos de Venda (VEN010)");
        assertThat(alteracao.rodape()).doesNotContain("Data de atualização");
        PedidoAoModelo pedidoDoErro = modelo.geracoes().stream().map(ModeloDeMentira.Geracao::pedido)
                .filter(p -> p.esquemaJson().contains("\"resultadoObtido\"")).findFirst().orElseThrow();
        assertThat(pedidoDoErro.instrucao()).contains("chamado de erro").contains("[M1]").doesNotContain("{{");

        assertThatThrownBy(() -> gerenciar().salvarCampos(maria, id, new CamposInformados("x".repeat(41), null, null, null)))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("41 caracteres");
        assertThatThrownBy(() -> gerenciarSaidas().salvarTela(maria, id, redacao.estados(id).get(0).id(), "x"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("só existe nos chamados");
    }

    @Test
    void oVideoDaConversaViraPassosNaLinhaDoTempoEOsQuadrosEntramNoResumo() {
        cadastrarChave();
        modelo.padrao(p -> p.esquemaJson().contains("\"blocos\"") ? """
                {"assunto":"Erro ao gravar o pedido com desconto",
                 "blocos":[
                   {"tipo":"paragrafo","texto":"A cliente gravou a tela mostrando o erro.","fontes":["M1","A4@00:10"],"imagens":[]},
                   {"tipo":"passo","texto":"Ao gravar o pedido com desconto, apareceu o erro.","fontes":["A4"],
                    "imagens":[{"id":"A4@00:12","legenda":""}]},
                   {"tipo":"passo","texto":"Fechou a mensagem.","fontes":["A4"],"imagens":[{"id":"A4@00:13","legenda":"inventado"}]}],
                 "base":{"cliente":"Loja Exemplo"},"telaFuncionalidade":"Pedidos (VEN010)"}""" : ModeloDeMentira.respostaPadrao(p));
        UUID id = criar();
        iniciarFila(FfmpegDeMentira.comando());

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        LinhaDoTempoView linha = gerenciarSaidas().linhaDoTempo(maria, id);
        assertThat(linha.itens()).filteredOn(i -> i.id().equals("A4")).singleElement().satisfies(i -> {
            assertThat(i.situacao()).isEqualTo("analisado");
            assertThat(i.texto()).contains("Passo 2 [00:10]: Grava e aparece o erro.")
                    .contains("Mensagem na tela (erro): \"Erro ao gravar o pedido\"").contains("Quadro do passo em 00:12")
                    .contains("Narração:").contains("[omitido]").doesNotContain("123 456 789");
        });
        PedidoAoModelo pedido = redacoes().get(0);
        assertThat(pedido.instrucao()).contains("os quadros dos vídeos")
                .contains("- A4@00:03: quadro do vídeo em 00:03: tela Pedidos (VEN010): abre um pedido novo")
                .contains("- A4@00:12: quadro do vídeo em 00:12").contains("- A4@00:30: quadro do vídeo em 00:30");

        SaidaView saida = gerenciarSaidas().detalhe(maria, id, resumo(id).id());
        assertThat(saida.html()).contains("data-fontes=\"M1 A4\"").contains("<strong>Imagem 1</strong>: quadro do vídeo em 00:12");
        assertThat(saida.imagens()).singleElement().satisfies(i -> {
            assertThat(i.arquivo()).isEqualTo("Imagem_01.png");
            assertThat(i.disponivel()).isTrue();
        });
        assertThat(saida.avisos()).anyMatch(a -> a.contains("1 imagem citada não está")).noneMatch(a -> a.contains("ffmpeg"));
        assertThat(PostgresDeTeste.jdbc().sql("SELECT instante_video_ms FROM atendimento.imagem WHERE saida_id = ?")
                .param(resumo(id).id()).query(Integer.class).list()).containsExactly(12_000);
    }

    @Test
    void semChaveOResumoFalhaComOAvisoEOAtendimentoFicaComFalhas() {
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));

        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        EstadoDaSaida estado = resumo(id);
        assertThat(estado.situacao()).isEqualTo("falhou");
        assertThat(estado.erro()).contains("Cadastre sua chave do Gemini");
        assertThat(modelo.geracoes()).isEmpty();

        // com a chave cadastrada, "Processar" de novo cria a versão seguinte e termina
        cadastrarChave();
        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> resumo(id).situacao().equals("pronta"));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(resumo(id).versao()).isEqualTo(2);
    }

    @Test
    void respostaForaDoEsquemaDuasVezesFalhaSoOTexto() {
        cadastrarChave();
        modelo.padrao(p -> p.esquemaJson().contains("\"blocos\"") ? "isto não é JSON" : ModeloDeMentira.respostaPadrao(p));
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));

        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        assertThat(resumo(id).situacao()).isEqualTo("falhou");
        assertThat(resumo(id).erro()).contains("Resposta inválida do Gemini");
        assertThat(redacoes()).as("uma nova tentativa na hora").hasSize(2);
        SaidaView falhou = gerenciarSaidas().detalhe(maria, id, resumo(id).id());
        assertThat(falhou.situacao()).isEqualTo("falhou");
        assertThat(falhou.html()).isNull();
        assertThat(falhou.imagens()).isEmpty();
    }

    @Test
    void cotaDoDiaNaRedacaoPausaERetomarTermina() {
        cadastrarChave();
        AtomicInteger vezes = new AtomicInteger();
        modelo.padrao(p -> {
            if (p.esquemaJson().contains("\"blocos\"") && vezes.incrementAndGet() == 1) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.COTA_DIARIA, "cota", null, 429);
            }
            return ModeloDeMentira.respostaPadrao(p);
        });
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));

        esperarAte(id, SituacaoDoAtendimento.PAUSADO);
        assertThat(resumo(id).situacao()).isEqualTo("pendente");
        assertThat(resumo(id).erro()).contains("cota diária");

        processamento().retomar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(resumo(id).situacao()).isEqualTo("pronta");
    }

    private UUID arquivoChamado(UUID id, String nome) {
        return arquivos.doAtendimento(id).stream().filter(a -> nome.equals(a.nome())).findFirst().orElseThrow().id();
    }

    private static boolean ehVideo(PedidoAoModelo p) {
        return p.esquemaJson().contains("\"instanteDoQuadro\"");
    }

    @Test
    void geminiSobrecarregadoNaAnaliseDoVideoPausaSemEscreverNadaEOsTextosSaemDepoisDoRetomar() {
        relogio = Clock.offset(Clock.system(FUSO), Duration.between(Instant.now(), AGORA)); // as tentativas esperam
        cadastrarChave();
        AtomicInteger sobrecarga = new AtomicInteger(1);
        modelo.padrao(p -> {
            if (ehVideo(p) && sobrecarga.get() == 1) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.INDISPONIVEL, "Gemini indisponível (HTTP 503).", null, 503);
            }
            return ModeloDeMentira.respostaPadrao(p);
        });
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO, TipoDeSaida.DEV_ALTERACAO));

        esperarAte(id, SituacaoDoAtendimento.PAUSADO);
        assertThat(atendimentos.estado(id).orElseThrow().motivoPausa()).contains("sobrecarregado")
                .contains("sem analisar o vídeo gravacao-tela.mp4").contains("Retomar");
        assertThat(modelo.geracoes()).filteredOn(g -> ehVideo(g.pedido())).as("as 3 tentativas").hasSize(3);
        assertThat(redacoes()).as("nenhum texto escrito sem o vídeo").isEmpty();
        assertThat(redacao.estados(id)).allSatisfy(s -> assertThat(s.situacao()).isEqualTo("pendente"));

        sobrecarga.set(0);
        processamento().retomar(maria, id);

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(resumo(id).situacao()).isEqualTo("pronta");
        assertThat(resumo(id).versao()).isEqualTo(1);
        assertThat(redacoes()).singleElement().satisfies(p -> assertThat(p.instrucao()).contains("Gravação da tela emitindo"));
        SaidaView saida = gerenciarSaidas().detalhe(maria, id, resumo(id).id());
        assertThat(saida.avisos()).noneMatch(a -> a.contains("Escrito sem o conteúdo"));
    }

    @Test
    void gerarDeNovoRefazAntesAAnaliseQueTinhaFalhadoPorSobrecarga() {
        cadastrarChave();
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        // como ficou um atendimento da versão anterior: a análise do vídeo falhou de vez com o Gemini sobrecarregado
        UUID video = arquivoChamado(id, "gravacao-tela.mp4");
        fila.esquecer(video);
        PostgresDeTeste.jdbc().sql("""
                        UPDATE atendimento.tarefa SET situacao = 'falhou', erro_codigo = 'GEMINI_INDISPONIVEL'
                         WHERE arquivo_id = ? AND tipo = 'analisar'
                        """).param(video).update();
        int antes = modelo.geracoes().size();

        processamento().gerar(maria, id, TipoDeSaida.RESUMO);

        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> resumo(id).versao() == 2 && resumo(id).situacao().equals("pronta"));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        List<ModeloDeMentira.Geracao> depois = modelo.geracoes().subList(antes, modelo.geracoes().size());
        assertThat(depois).as("primeiro o vídeo, depois o texto").hasSize(2);
        assertThat(ehVideo(depois.get(0).pedido())).isTrue();
        assertThat(depois.get(1).pedido().instrucao()).contains("Gravação da tela emitindo");
    }

    @Test
    void textoEscritoSemAAnaliseAvisaEEGeradoDeNovoSozinhoQuandoElaChega() {
        cadastrarChave();
        AtomicInteger bloqueio = new AtomicInteger(1);
        modelo.padrao(p -> {
            if (ehVideo(p) && bloqueio.get() == 1) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.BLOQUEOU, "segurança");
            }
            return ModeloDeMentira.respostaPadrao(p);
        });
        UUID id = criar();
        iniciarFila();
        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO, TipoDeSaida.DEV_ERRO));
        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        EstadoDaSaida primeira = resumo(id);
        assertThat(primeira.situacao()).isEqualTo("pronta");
        assertThat(gerenciarSaidas().detalhe(maria, id, primeira.id()).avisos())
                .anyMatch(a -> a.startsWith("Escrito sem o conteúdo do vídeo gravacao-tela.mp4 (a análise falhou)")
                        && a.contains("gerado de novo sozinho"));
        // o chamado de erro foi editado pela pessoa: esse não muda sozinho
        EstadoDaSaida erro = redacao.estados(id).stream().filter(s -> s.tipo().equals("dev_erro")).findFirst().orElseThrow();
        gerenciarSaidas().salvarTexto(maria, id, erro.id(), "<p data-fontes=\"M1\">Texto revisado à mão.</p>");

        bloqueio.set(0);
        processamento().tentarDeNovo(maria, id, arquivoChamado(id, "gravacao-tela.mp4"));

        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> resumo(id).versao() == 2 && resumo(id).situacao().equals("pronta"));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(gerenciarSaidas().detalhe(maria, id, resumo(id).id()).avisos()).noneMatch(a -> a.contains("Escrito sem o conteúdo"));
        assertThat(redacoes().get(redacoes().size() - 1).instrucao()).contains("Gravação da tela emitindo");
        assertThat(redacao.estados(id)).filteredOn(s -> s.tipo().equals("dev_erro")).singleElement()
                .satisfies(s -> assertThat(s.versao()).as("editado não muda").isEqualTo(1));
    }

    @Test
    void assuntoLongoPedeUmMaisCurto() {
        cadastrarChave();
        String longo = "Erro ao emitir a nota fiscal de saída no pedido de venda com desconto acima do limite permitido para "
                + "o cliente e a tabela de preços promocional do mês corrente da loja";
        modelo.padrao(p -> p.esquemaJson().contains("\"blocos\"")
                ? ModeloDeMentira.RESUMO.replace("Erro ao emitir a nota fiscal na tela de vendas", longo)
                : ModeloDeMentira.respostaPadrao(p));
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        SaidaView saida = gerenciarSaidas().detalhe(maria, id, resumo(id).id());
        assertThat(saida.assunto()).endsWith(" : Erro na nota fiscal");
        assertThat(modelo.geracoes()).anySatisfy(g -> assertThat(g.pedido().instrucao()).contains("Reescreva-o")
                .contains(longo).doesNotContain("{{"));
        assertThat(saida.tokensEntrada()).as("redação e assunto").isEqualTo(2000);
    }
}
