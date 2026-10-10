package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ConversaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.NovoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ResumoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ItemDaConversa;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Criar o atendimento a partir do PDF (leitor e pasta de verdade; o banco é simulado em memória). */
class GerenciarAtendimentosTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    /** Valores sintéticos do PDF de exemplo que nunca podem ser guardados nem ir para o log. */
    private static final List<String> SEGREDOS = List.of("4815162342", "4821", "12 345 678");

    @TempDir
    Path raiz;

    private final AtendimentosEmMemoria atendimentos = new AtendimentosEmMemoria();
    private Usuario maria;
    private Usuario joao;

    @BeforeEach
    void preparar() {
        maria = usuario("maria");
        joao = usuario("joao");
    }

    private GerenciarAtendimentos gerenciar(Instant agora, DataSize tamanhoMaximo) {
        return new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, new PastaEmDisco(raiz),
                TransactionOperations.withoutTransaction(), Clock.fixed(agora, FUSO), tamanhoMaximo, 30, 180);
    }

    private GerenciarAtendimentos gerenciar() {
        return gerenciar(AGORA, DataSize.ofMegabytes(50));
    }

    private static ResultadoDoEnvio enviar(GerenciarAtendimentos gerenciar, Usuario usuario, byte[] pdf) {
        return gerenciar.receberPdf(usuario, new ByteArrayInputStream(pdf), pdf.length, "Conversa do chamado.pdf");
    }

    @Test
    void criaOAtendimentoComAConversaLidaEMascarada() throws IOException {
        ResultadoDoEnvio resultado = enviar(gerenciar(), maria, PdfDigisacDeTeste.exemplo().bytes());

        assertThat(resultado.criado()).isTrue();
        LeituraView leitura = resultado.leitura();
        assertThat(leitura.arquivo()).isEqualTo("Conversa do chamado.pdf");
        assertThat(leitura.chamado()).isEqualTo(PdfDigisacDeTeste.CHAMADO);
        assertThat(leitura.contato()).isEqualTo("Loja Exemplo - Maria");
        assertThat(leitura.mensagens()).isEqualTo(14);
        assertThat(leitura.eventos()).isEqualTo(3);
        assertThat(leitura.anexos()).isEqualTo(4);
        assertThat(leitura.anexosPorCategoria()).containsExactly(Map.entry("audio", 1), Map.entry("documento", 1),
                Map.entry("imagem", 1), Map.entry("video", 1));
        assertThat(leitura.linksValidosAte()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        assertThat(leitura.linksVencidos()).isFalse();
        assertThat(leitura.omitidos().chaveDoBot()).isEqualTo(1);
        assertThat(leitura.omitidos().acessoRemoto()).isEqualTo(2);

        ResumoView atendimento = resultado.atendimento();
        assertThat(atendimento.situacao()).isEqualTo("novo");
        assertThat(atendimento.mensagens()).isEqualTo(14);
        assertThat(atendimento.anexos()).isEqualTo(4);

        NovoAtendimento guardado = atendimentos.porId.get(atendimento.id());
        assertThat(guardado.usuarioId()).isEqualTo(maria.id());
        assertThat(guardado.apagarArquivosEm()).isEqualTo(Instant.parse("2026-11-08T15:00:00Z"));
        assertThat(guardado.apagarTextosEm()).isEqualTo(Instant.parse("2027-04-07T15:00:00Z"));
        String textos = String.join("\n", guardado.conversa().itens().stream().map(ItemDaConversa::texto).toList());
        assertThat(textos).contains(Mascaramento.OMITIDO);
        SEGREDOS.forEach(segredo -> assertThat(textos).doesNotContain(segredo));

        assertThat(raiz.resolve(atendimento.id().toString()).resolve("conversa.pdf")).exists();
        assertThat(arquivosRecebendo()).isEmpty();
    }

    @Test
    void oDetalheTrazAConversaEOsAnexosSemOLink() {
        GerenciarAtendimentos gerenciar = gerenciar();
        UUID id = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()).atendimento().id();

        AtendimentoView detalhe = gerenciar.detalhe(maria, id);

        assertThat(detalhe.itens()).hasSize(17);
        assertThat(detalhe.itens().get(0).tipo()).isEqualTo("evento");
        assertThat(detalhe.itens().get(1).lado()).isEqualTo("cliente");
        assertThat(detalhe.anexos()).extracting(AtendimentoView.Anexo::nome)
                .containsExactly("1760011620000.jpeg", "1760011680000.oga", "Relatório de vendas.pdf", "gravacao-tela.mp4");
        assertThat(detalhe.anexos()).allSatisfy(a -> {
            assertThat(a.situacao()).isEqualTo("aguardando");
            assertThat(a.vencido()).isFalse();
            assertThat(detalhe.itens().get(a.mensagemOrdem() - 1).anexos()).contains(a.ordem());
        });
        assertThat(detalhe.toString()).doesNotContain("X-Amz").doesNotContain("objectstorage");
        assertThat(detalhe.linksVencidos()).isFalse();
        assertThat(detalhe.versaoLeitor()).isEqualTo(1);
    }

    @Test
    void depoisDe24HorasOsLinksAparecemVencidos() {
        Instant depois = Instant.parse("2026-10-10T12:00:00Z");
        GerenciarAtendimentos gerenciar = gerenciar(depois, DataSize.ofMegabytes(50));

        ResultadoDoEnvio resultado = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes());

        assertThat(resultado.leitura().linksVencidos()).isTrue();
        assertThat(resultado.atendimento().linksVencidos()).isTrue();
        assertThat(gerenciar.detalhe(maria, resultado.atendimento().id()).anexos()).allMatch(AtendimentoView.Anexo::vencido);
        assertThat(gerenciar.listar(maria)).singleElement().extracting(ResumoView::linksVencidos).isEqualTo(true);
    }

    @Test
    void oMesmoChamadoNaoCriaOutroAtendimento() throws IOException {
        GerenciarAtendimentos gerenciar = gerenciar();
        UUID primeiro = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()).atendimento().id();

        ResultadoDoEnvio deNovo = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes());

        assertThat(deNovo.criado()).isFalse();
        assertThat(deNovo.atendimento().id()).isEqualTo(primeiro);
        assertThat(deNovo.leitura().mensagens()).isEqualTo(14);
        assertThat(atendimentos.porId).hasSize(1);
        assertThat(arquivosRecebendo()).isEmpty();
        try (Stream<Path> pastas = Files.list(raiz)) {
            assertThat(pastas.filter(p -> !p.getFileName().toString().startsWith("."))).hasSize(1);
        }
    }

    @Test
    void outraPessoaComOMesmoChamadoTemOProprioAtendimento() {
        GerenciarAtendimentos gerenciar = gerenciar();
        UUID daMaria = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()).atendimento().id();

        ResultadoDoEnvio doJoao = enviar(gerenciar, joao, PdfDigisacDeTeste.exemplo().bytes());

        assertThat(doJoao.criado()).isTrue();
        assertThat(doJoao.atendimento().id()).isNotEqualTo(daMaria);
        assertThat(gerenciar.listar(joao)).extracting(ResumoView::id).containsExactly(doJoao.atendimento().id());
    }

    @Test
    void pdfRecusadoNaoDeixaNada() throws IOException {
        GerenciarAtendimentos gerenciar = gerenciar();

        assertThatThrownBy(() -> enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().titulo("Relatório").bytes()))
                .hasFieldOrPropertyWithValue("codigo", "PDF_NAO_E_DIGISAC");
        assertThatThrownBy(() -> enviar(gerenciar, maria, "não é PDF".getBytes()))
                .hasFieldOrPropertyWithValue("codigo", "PDF_INVALIDO");

        assertThat(atendimentos.porId).isEmpty();
        assertThat(arquivosRecebendo()).isEmpty();
    }

    @Test
    void pdfGrandeDemaisPeloTamanhoInformadoNemELido() {
        GerenciarAtendimentos gerenciar = gerenciar(AGORA, DataSize.ofKilobytes(10));
        InputStream naoPodeSerLido = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("o corpo não devia ser lido");
            }
        };

        assertThatThrownBy(() -> gerenciar.receberPdf(maria, naoPodeSerLido, 20_000, "grande.pdf"))
                .hasFieldOrPropertyWithValue("codigo", "PDF_GRANDE_DEMAIS")
                .hasMessageContaining("MB");
    }

    @Test
    void pdfGrandeDemaisSemTamanhoInformadoParaNoLimite() throws IOException {
        GerenciarAtendimentos gerenciar = gerenciar(AGORA, DataSize.ofKilobytes(1));
        byte[] pdf = PdfDigisacDeTeste.exemplo().bytes();

        assertThatThrownBy(() -> gerenciar.receberPdf(maria, new ByteArrayInputStream(pdf), -1, null))
                .hasFieldOrPropertyWithValue("codigo", "PDF_GRANDE_DEMAIS");
        assertThat(arquivosRecebendo()).isEmpty();
    }

    @Test
    void falhaAoGravarNaoDeixaPastaNemTemporario() throws IOException {
        atendimentos.falharAoSalvar = true;
        GerenciarAtendimentos gerenciar = gerenciar();

        assertThatThrownBy(() -> enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(arquivosRecebendo()).isEmpty();
        try (Stream<Path> pastas = Files.list(raiz)) {
            assertThat(pastas.filter(p -> !p.getFileName().toString().startsWith("."))).isEmpty();
        }
    }

    @Test
    void cadaUmVeSoOsProprios() {
        GerenciarAtendimentos gerenciar = gerenciar();
        UUID daMaria = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()).atendimento().id();

        assertThat(gerenciar.listar(joao)).isEmpty();
        assertNaoEncontrado(() -> gerenciar.detalhe(joao, daMaria));
        assertNaoEncontrado(() -> gerenciar.apagar(joao, daMaria));
        assertThat(atendimentos.porId).containsKey(daMaria);
        assertThat(raiz.resolve(daMaria.toString())).exists();
    }

    @Test
    void apagaOAtendimentoEOsArquivos() {
        GerenciarAtendimentos gerenciar = gerenciar();
        UUID id = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()).atendimento().id();

        gerenciar.apagar(maria, id);

        assertThat(atendimentos.porId).isEmpty();
        assertThat(raiz.resolve(id.toString())).doesNotExist();
        assertNaoEncontrado(() -> gerenciar.detalhe(maria, id));
        assertNaoEncontrado(() -> gerenciar.apagar(maria, id));
    }

    @Test
    void oLogNaoTemConteudoDaConversaNemLinks() {
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> linhas = new ListAppender<>();
        linhas.start();
        logger.addAppender(linhas);
        try {
            GerenciarAtendimentos gerenciar = gerenciar();
            UUID id = enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes()).atendimento().id();
            enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().bytes());
            gerenciar.detalhe(maria, id);
            try {
                enviar(gerenciar, maria, PdfDigisacDeTeste.exemplo().titulo("Outro").bytes());
            } catch (DominioException esperada) {
                // recusado
            }
            gerenciar.apagar(maria, id);
        } finally {
            logger.detachAppender(linhas);
        }

        assertThat(linhas.list).isNotEmpty();
        List<String> proibidos = new ArrayList<>(SEGREDOS);
        proibidos.addAll(List.of("Maria", "Loja Exemplo", "nota fiscal", "Bom dia", "X-Amz", "objectstorage",
                "Relatório", "1760011620000", PdfDigisacDeTeste.CHAMADO));
        assertThat(linhas.list).allSatisfy(l -> proibidos.forEach(p -> assertThat(l.getFormattedMessage()).doesNotContain(p)));
    }

    // --------------------------------------------------------------------------------------------- apoio

    private List<Path> arquivosRecebendo() throws IOException {
        Path recebendo = raiz.resolve(".recebendo");
        if (!Files.exists(recebendo)) {
            return List.of();
        }
        try (Stream<Path> arquivos = Files.list(recebendo)) {
            return arquivos.toList();
        }
    }

    private static void assertNaoEncontrado(org.assertj.core.api.ThrowableAssert.ThrowingCallable acao) {
        assertThatThrownBy(acao).isInstanceOf(RecursoNaoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "ATENDIMENTO_NAO_ENCONTRADO");
    }

    private static Usuario usuario(String login) {
        return new Usuario(UUID.randomUUID(), login, login, "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
    }

    /** O banco em memória, com as mesmas regras do de verdade: tudo filtrado pelo dono. */
    static final class AtendimentosEmMemoria implements Atendimentos {
        final Map<UUID, NovoAtendimento> porId = new LinkedHashMap<>();
        boolean falharAoSalvar;

        @Override
        public void salvarNovo(NovoAtendimento atendimento) {
            if (falharAoSalvar) {
                throw new IllegalStateException("banco fora do ar");
            }
            porId.put(atendimento.id(), atendimento);
        }

        @Override
        public List<ResumoDoAtendimento> doUsuario(UUID usuarioId) {
            return porId.values().stream().filter(a -> a.usuarioId().equals(usuarioId))
                    .sorted(Comparator.comparing(NovoAtendimento::criadoEm).reversed()).map(AtendimentosEmMemoria::resumo).toList();
        }

        @Override
        public Optional<AtendimentoGuardado> buscar(UUID id, UUID usuarioId) {
            return Optional.ofNullable(porId.get(id)).filter(a -> a.usuarioId().equals(usuarioId)).map(a -> {
                List<ArquivoDoAtendimento> arquivos = a.conversa().anexos().stream()
                        .map(x -> new ArquivoDoAtendimento(UUID.randomUUID(), "anexo_conversa", x.ordem(), x.nome(),
                                x.categoria(), "aguardando", x.validoAte(), x.mensagemOrdem(), x.momento()))
                        .toList();
                return new AtendimentoGuardado(resumo(a), new ConversaGuardada(a.conversa().cabecalho(), a.conversa().itens(),
                        a.omitidos()), arquivos, a.versaoLeitor(), a.apagarArquivosEm());
            });
        }

        @Override
        public boolean existe(UUID id, UUID usuarioId) {
            return buscar(id, usuarioId).isPresent();
        }

        @Override
        public Optional<ResumoDoAtendimento> doChamado(UUID usuarioId, String chamado) {
            return doUsuario(usuarioId).stream().filter(r -> r.chamado().equals(chamado)).findFirst();
        }

        @Override
        public boolean apagar(UUID id, UUID usuarioId) {
            return existe(id, usuarioId) && porId.remove(id) != null;
        }

        private static ResumoDoAtendimento resumo(NovoAtendimento a) {
            var c = a.conversa().cabecalho();
            return new ResumoDoAtendimento(a.id(), c.chamado(), c.contato(), c.inicio(), c.fim(), SituacaoDoAtendimento.NOVO,
                    a.criadoEm(), (int) a.conversa().mensagens(), a.conversa().anexos().size(), a.conversa().linksValidosAte());
        }
    }
}
