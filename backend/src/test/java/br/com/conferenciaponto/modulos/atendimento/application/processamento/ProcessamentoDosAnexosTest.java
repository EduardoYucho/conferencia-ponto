package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ServidorDeAnexos;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.fila.CoordenadorDaFila;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.BaixadorHttp;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.PoliticaDeRede;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
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
 * Do PDF ao anexo baixado: leitura, fila, download contra um servidor HTTP local e a situação do atendimento (banco
 * e pasta de verdade).
 */
class ProcessamentoDosAnexosTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 'J', 'F', 'I', 'F', 0, 1, 2, 3);
    private static final byte[] OGG = "OggS\0\2\0\0\0\0\0\0opus".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] PDF = "%PDF-1.7\nrelatorio".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] MP4 = "\0\0\0\u0018ftypisom\0\0\0\0video".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    @TempDir
    Path raiz;

    private final ServidorDeAnexos servidor = new ServidorDeAnexos();
    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();
    private final Arquivos arquivos = RepositoriosDeTeste.arquivos();
    private final Tarefas tarefas = RepositoriosDeTeste.tarefas();
    private final List<ProgressoAtualizado> eventos = new CopyOnWriteArrayList<>();
    private final List<CoordenadorDaFila> coordenadores = new ArrayList<>();
    private final PublicadorDeProgresso publicador = new PublicadorDeProgresso(atendimentos, arquivos,
            evento -> eventos.add((ProgressoAtualizado) evento));
    private Usuario maria;
    private Usuario joao;

    private static byte[] bytes(int... valores) {
        byte[] b = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) {
            b[i] = (byte) valores[i];
        }
        return b;
    }

    @BeforeEach
    void preparar() {
        PostgresDeTeste.jdbc().sql("DELETE FROM atendimento.tarefa").update();
        maria = usuario(PostgresDeTeste.novoUsuario(true), "maria");
        joao = usuario(PostgresDeTeste.novoUsuario(true), "joao");
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

    private Clock relogio(Instant agora) {
        return Clock.fixed(agora, FUSO);
    }

    private ProcessarAtendimentos processamento(Instant agora) {
        return new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio(agora), 2);
    }

    private CoordenadorDaFila coordenador(Instant agora, DataSize espacoMinimo) {
        BaixarAnexo baixar = new BaixarAnexo(arquivos, new PastaEmDisco(raiz),
                new BaixadorHttp(new PoliticaDeRede(List.of("127.0.0.1"), false, true, null), Duration.ofSeconds(2),
                        Duration.ofSeconds(1)),
                publicador, relogio(agora), DataSize.ofMegabytes(10), espacoMinimo, 4);
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(baixar), processamento(agora), relogio(agora), 8, 4,
                Duration.ofMinutes(15), Duration.ofMillis(200), Duration.ofMillis(1), Duration.ofMillis(5), true);
        coordenadores.add(c);
        return c;
    }

    private UUID criar(Instant agora, String dataDosLinks) {
        GerenciarAtendimentos gerenciar = new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador,
                new PastaEmDisco(raiz), RepositoriosDeTeste.transacao(), relogio(agora), DataSize.ofMegabytes(50), 30, 180);
        byte[] pdf = PdfDigisacDeTeste.exemplo(dataDosLinks, servidor.base()).bytes();
        return gerenciar.receberPdf(maria, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
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

    // --------------------------------------------------------------------------------------------- testes

    @Test
    void baixaOsAnexosConfereOTipoEDeixaOAtendimentoPronto() throws IOException {
        UUID id = criar(AGORA, "20261009T120000Z");
        eventos.clear();

        ProgressoView inicio = processamento(AGORA).processar(maria, id);
        assertThat(inicio.situacao()).isEqualTo("processando");
        assertThat(inicio.percentual()).isZero();
        coordenador(AGORA, DataSize.ofKilobytes(1)).varrer();

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        List<ArquivoDoAtendimento> baixados = arquivos.doAtendimento(id);
        assertThat(baixados).hasSize(4).allSatisfy(a -> {
            assertThat(a.situacao()).isEqualTo("pronto");
            assertThat(a.erroMensagem()).isNull();
            assertThat(raiz.resolve(a.caminho())).exists();
        });
        assertThat(Files.readAllBytes(raiz.resolve(anexo(id, "1760011620000.jpeg").caminho()))).isEqualTo(JPEG);
        assertThat(anexo(id, "Relatório de vendas.pdf").tamanho()).isEqualTo(PDF.length);
        assertThat(anexo(id, "gravacao-tela.mp4").caminho()).endsWith("anexo_004_gravacao-tela.mp4");
        assertThat(PostgresDeTeste.jdbc().sql("SELECT count(*) FROM atendimento.arquivo WHERE atendimento_id = ? AND url_download IS NOT NULL")
                .param(id).query(Long.class).single()).as("o link sai do banco depois do download").isZero();
        assertThat(PostgresDeTeste.jdbc().sql("SELECT sha256 FROM atendimento.arquivo WHERE atendimento_id = ? ORDER BY ordem")
                .param(id).query(String.class).list()).allMatch(s -> s.matches("[0-9a-f]{64}"));
        assertThat(PostgresDeTeste.jdbc().sql("SELECT tipo_midia FROM atendimento.arquivo WHERE atendimento_id = ? ORDER BY ordem")
                .param(id).query(String.class).list()).containsExactly("image/jpeg", "audio/ogg", "application/pdf", "video/mp4");
        assertThat(eventos).isNotEmpty().allMatch(e -> e.usuarioId().equals(maria.id()));
        assertThat(eventos.get(eventos.size() - 1).progresso()).extracting(ProgressoView::situacao, ProgressoView::percentual)
                .containsExactly("pronto", 100);
        assertThat(raiz.resolve(id.toString()).resolve("arquivos")).isDirectoryNotContaining("glob:**/.parcial-*");
    }

    @Test
    void umAnexoQueFalhaNaoParaOsOutrosEPodeSerTentadoDeNovo() {
        servidor.status(PdfDigisacDeTeste.CAMINHO_DO_DOCUMENTO, 404);
        UUID id = criar(AGORA, "20261009T120000Z");
        CoordenadorDaFila coordenador = coordenador(AGORA, DataSize.ofKilobytes(1));

        processamento(AGORA).processar(maria, id);
        coordenador.varrer();

        esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        ArquivoDoAtendimento documento = anexo(id, "Relatório de vendas.pdf");
        assertThat(documento.situacao()).isEqualTo("falhou");
        assertThat(documento.erroMensagem()).contains("não existe mais");
        assertThat(arquivos.doAtendimento(id)).filteredOn(a -> !a.id().equals(documento.id()))
                .allMatch(a -> a.situacao().equals("pronto"));

        servidor.arquivo(PdfDigisacDeTeste.CAMINHO_DO_DOCUMENTO, PDF);
        assertThat(processamento(AGORA).tentarDeNovo(maria, id, documento.id()).situacao()).isEqualTo("processando");
        coordenador.varrer();

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(anexo(id, "Relatório de vendas.pdf").situacao()).isEqualTo("pronto");
        assertThatThrownBy(() -> processamento(AGORA).tentarDeNovo(maria, id, documento.id()))
                .hasFieldOrPropertyWithValue("codigo", "NADA_A_TENTAR");
    }

    @Test
    void linkVencidoNemVaiParaARede() {
        Instant depois = Instant.parse("2026-10-11T00:00:00Z");
        UUID id = criar(AGORA, "20261009T120000Z");

        ProgressoView progresso = processamento(depois).processar(maria, id);

        assertThat(progresso.situacao()).isEqualTo("com_falhas");
        assertThat(progresso.arquivos()).allSatisfy(a -> {
            assertThat(a.situacao()).isEqualTo("vencido");
            assertThat(a.erro()).contains("Exporte a conversa de novo");
        });
        assertThat(servidor.pedidos()).isEmpty();
        UUID qualquer = progresso.arquivos().get(0).id();
        assertThatThrownBy(() -> processamento(depois).tentarDeNovo(maria, id, qualquer))
                .hasFieldOrPropertyWithValue("codigo", "LINK_VENCIDO")
                .hasMessageContaining("venceu em 10/10 às 09:00");
    }

    @Test
    void pdfNovoDoMesmoChamadoRenovaOsLinksEOsAnexosSaoBaixados() {
        Instant depois = Instant.parse("2026-10-11T00:00:00Z");
        UUID id = criar(AGORA, "20261009T120000Z");
        processamento(depois).processar(maria, id);
        assertThat(situacao(id)).isEqualTo(SituacaoDoAtendimento.COM_FALHAS);

        assertThat(criar(depois, "20261010T230000Z")).as("o mesmo atendimento").isEqualTo(id);
        processamento(depois).processar(maria, id);
        coordenador(depois, DataSize.ofKilobytes(1)).varrer();

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(arquivos.doAtendimento(id)).allMatch(a -> a.situacao().equals("pronto"));
    }

    @Test
    void discoCheioPausaOAtendimentoERetomarContinua() {
        UUID id = criar(AGORA, "20261009T120000Z");
        processamento(AGORA).processar(maria, id);

        coordenador(AGORA, DataSize.ofBytes(Long.MAX_VALUE / 2)).varrer();

        esperarAte(id, SituacaoDoAtendimento.PAUSADO);
        assertThat(atendimentos.estado(id).orElseThrow().motivoPausa()).contains("disco do servidor está cheio");
        await().atMost(10, TimeUnit.SECONDS).until(() -> tarefas.ativas(id) == 0);
        assertThat(servidor.pedidos()).isEmpty();

        coordenadores.forEach(CoordenadorDaFila::parar);
        ProgressoView retomado = processamento(AGORA).retomar(maria, id);
        assertThat(retomado.situacao()).isEqualTo("processando");
        assertThat(retomado.motivoPausa()).isNull();
        coordenador(AGORA, DataSize.ofKilobytes(1)).varrer();
        esperarAte(id, SituacaoDoAtendimento.PRONTO);
    }

    @Test
    void cancelarTiraOQueAindaNaoComecou() {
        UUID id = criar(AGORA, "20261009T120000Z");
        processamento(AGORA).processar(maria, id);

        ProgressoView cancelado = processamento(AGORA).cancelar(maria, id);
        coordenador(AGORA, DataSize.ofKilobytes(1)).varrer();

        assertThat(cancelado.situacao()).isEqualTo("cancelado");
        assertThat(tarefas.ativas(id)).isZero();
        assertThat(servidor.pedidos()).isEmpty();
        assertThat(processamento(AGORA).retomar(maria, id).situacao()).as("processar de novo").isEqualTo("processando");
    }

    @Test
    void cadaUmSoMexeNosProprios() {
        UUID id = criar(AGORA, "20261009T120000Z");
        UUID arquivo = arquivos.doAtendimento(id).get(0).id();

        for (Runnable acao : List.<Runnable>of(() -> processamento(AGORA).processar(joao, id),
                () -> processamento(AGORA).cancelar(joao, id), () -> processamento(AGORA).retomar(joao, id),
                () -> processamento(AGORA).progresso(joao, id), () -> processamento(AGORA).tentarDeNovo(joao, id, arquivo))) {
            assertThatThrownBy(acao::run).isInstanceOf(RecursoNaoEncontradoException.class)
                    .hasFieldOrPropertyWithValue("codigo", "ATENDIMENTO_NAO_ENCONTRADO");
        }
        assertThat(tarefas.ativas(id)).isZero();
    }

    @Test
    void oLogNaoTemOsLinksNemOsNomesDosAnexos() {
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> linhas = new ListAppender<>();
        linhas.start();
        logger.addAppender(linhas);
        servidor.status(PdfDigisacDeTeste.CAMINHO_DO_DOCUMENTO, 403);
        try {
            UUID id = criar(AGORA, "20261009T120000Z");
            processamento(AGORA).processar(maria, id);
            coordenador(AGORA, DataSize.ofKilobytes(1)).varrer();
            esperarAte(id, SituacaoDoAtendimento.COM_FALHAS);
        } finally {
            logger.detachAppender(linhas);
        }

        assertThat(linhas.list).isNotEmpty();
        for (String proibido : Set.of("X-Amz", "digisac-storage", "Relatório", "1760011620000", "gravacao-tela", "Maria")) {
            assertThat(linhas.list).noneMatch(l -> l.getFormattedMessage().contains(proibido));
        }
    }

    private static Usuario usuario(UUID id, String login) {
        return new Usuario(id, login, login, "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
    }
}
