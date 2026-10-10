package br.com.conferenciaponto.modulos.atendimento.application.arquivo;

import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.PublicadorDeProgresso;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ligações, vídeo e prints enviados pela pessoa, o envio à mão de um anexo e tirar um arquivo. */
class GerenciarArquivosTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T15:00:00Z");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final byte[] MP3 = "ID3\4\0\0\0\0\0\0ligacao".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    @TempDir
    Path raiz;

    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();
    private final Arquivos arquivos = RepositoriosDeTeste.arquivos();
    private final Tarefas tarefas = RepositoriosDeTeste.tarefas();
    private final PublicadorDeProgresso publicador = new PublicadorDeProgresso(atendimentos, arquivos, evento -> { });
    private final Clock relogio = Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo"));
    private Usuario maria;
    private Usuario joao;
    private UUID atendimento;

    @BeforeEach
    void preparar() {
        maria = usuario(PostgresDeTeste.novoUsuario(true), "maria");
        joao = usuario(PostgresDeTeste.novoUsuario(true), "joao");
        byte[] pdf = PdfDigisacDeTeste.exemplo().bytes();
        atendimento = new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador, new PastaEmDisco(raiz),
                RepositoriosDeTeste.transacao(), relogio, DataSize.ofMegabytes(50), 30, 180)
                .receberPdf(maria, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
    }

    private GerenciarArquivos gerenciar(DataSize espacoMinimo) {
        return new GerenciarArquivos(atendimentos, arquivos, tarefas, new PastaEmDisco(raiz),
                new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio, 3), publicador, relogio,
                DataSize.ofKilobytes(1), DataSize.ofKilobytes(2), DataSize.ofKilobytes(4), DataSize.ofKilobytes(1), espacoMinimo);
    }

    private GerenciarArquivos gerenciar() {
        return gerenciar(DataSize.ofBytes(1));
    }

    private static InputStream corpo(byte[] conteudo) {
        return new ByteArrayInputStream(conteudo);
    }

    private ArquivoView enviar(String origem, String nome, byte[] conteudo) {
        return gerenciar().enviarExtra(maria, atendimento, origem, nome, "1760011000000", corpo(conteudo), conteudo.length);
    }

    private void recusado(Runnable acao, String codigo) {
        assertThatThrownBy(acao::run).isInstanceOf(DominioException.class).hasFieldOrPropertyWithValue("codigo", codigo);
    }

    private long arquivosNaPasta() throws IOException {
        Path pasta = raiz.resolve(atendimento.toString()).resolve("arquivos");
        if (!Files.exists(pasta)) {
            return 0;
        }
        try (var lista = Files.list(pasta)) {
            return lista.count();
        }
    }

    @Test
    void enviaUmPrintEUmaLigacaoComADataDoArquivo() throws IOException {
        ArquivoView print = enviar("print_extra", "Captura de tela.png", PNG);
        ArquivoView segundo = enviar("print_extra", "outra.png", PNG);
        ArquivoView ligacao = enviar("ligacao", "ligação com cliente.mp3", MP3);

        assertThat(print).extracting(ArquivoView::origem, ArquivoView::ordem, ArquivoView::categoria, ArquivoView::situacao,
                ArquivoView::tamanho).containsExactly("print_extra", 1, "imagem", "pronto", (long) PNG.length);
        assertThat(print.momento()).isEqualTo(Instant.ofEpochMilli(1760011000000L));
        assertThat(segundo.ordem()).isEqualTo(2);
        assertThat(ligacao).extracting(ArquivoView::ordem, ArquivoView::categoria).containsExactly(1, "audio");
        assertThat(ligacao.nome()).isEqualTo("ligação com cliente.mp3");
        List<ArquivoDoAtendimento> guardados = arquivos.doAtendimento(atendimento);
        assertThat(guardados).filteredOn(a -> !a.daConversa()).extracting(ArquivoDoAtendimento::caminho)
                .containsExactly(atendimento + "/arquivos/ligacao_001_ligacao_com_cliente.mp3",
                        atendimento + "/arquivos/print_001_Captura_de_tela.png", atendimento + "/arquivos/print_002_outra.png");
        assertThat(Files.readAllBytes(raiz.resolve(atendimento + "/arquivos/print_001_Captura_de_tela.png"))).isEqualTo(PNG);
        assertThat(raiz.resolve(".recebendo")).isEmptyDirectory();
    }

    @Test
    void oNomeNuncaViraCaminho() {
        ArquivoView print = enviar("print_extra", "..\\..\\..\\Windows\\fora.png", PNG);

        assertThat(print.nome()).isEqualTo("fora.png");
        assertThat(arquivos.buscar(atendimento, print.id()).orElseThrow().caminho())
                .isEqualTo(atendimento + "/arquivos/print_001_fora.png");
    }

    @Test
    void recusasNoEnvio() throws IOException {
        recusado(() -> enviar("anexo_conversa", "a.png", PNG), "ORIGEM_INVALIDA");
        recusado(() -> enviar("qualquer", "a.png", PNG), "ORIGEM_INVALIDA");
        recusado(() -> enviar("print_extra", "relatorio.docx", ZIP), "TIPO_NAO_SUPORTADO");
        recusado(() -> enviar("print_extra", "falso.png", ZIP), "TIPO_NAO_SUPORTADO");
        recusado(() -> enviar("print_extra", "  ", PNG), "NOME_OBRIGATORIO");
        recusado(() -> enviar("print_extra", "vazio.png", new byte[0]), "ARQUIVO_VAZIO");
        byte[] grande = new byte[2000];
        System.arraycopy(PNG, 0, grande, 0, PNG.length);
        recusado(() -> enviar("print_extra", "grande.png", grande), "ARQUIVO_GRANDE_DEMAIS");
        recusado(() -> gerenciar().enviarExtra(maria, atendimento, "print_extra", "grande.png", null, corpo(grande), -1),
                "ARQUIVO_GRANDE_DEMAIS");
        recusado(() -> gerenciar(DataSize.ofTerabytes(1000)).enviarExtra(maria, atendimento, "print_extra", "a.png", null,
                corpo(PNG), PNG.length), "DISCO_CHEIO");
        recusado(() -> gerenciar().enviarExtra(joao, atendimento, "print_extra", "a.png", null, corpo(PNG), PNG.length),
                "ATENDIMENTO_NAO_ENCONTRADO");

        assertThat(arquivosNaPasta()).isZero();
        assertThat(arquivos.doAtendimento(atendimento)).allMatch(ArquivoDoAtendimento::daConversa);
        if (Files.exists(raiz.resolve(".recebendo"))) {
            assertThat(raiz.resolve(".recebendo")).isEmptyDirectory();
        }
    }

    @Test
    void dataDeModificacaoImpossivelFicaSemHorario() {
        assertThat(GerenciarArquivos.momento("abc", AGORA)).isNull();
        assertThat(GerenciarArquivos.momento(String.valueOf(AGORA.plusSeconds(3 * 86400).toEpochMilli()), AGORA)).isNull();
        assertThat(GerenciarArquivos.momento("2026-10-09T10:00:00Z", AGORA)).isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
        assertThat(GerenciarArquivos.momento(null, AGORA)).isNull();
    }

    @Test
    void oAnexoQueFalhouPodeSerEnviadoAMao() {
        ArquivoDoAtendimento imagem = arquivos.doAtendimento(atendimento).get(0);
        arquivos.registrarFalha(imagem.id(), br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo.VENCIDO,
                "LINK_VENCIDO", "venceu");
        tarefas.criar(TipoDeTarefa.BAIXAR, atendimento, imagem.id(), null, 3, AGORA);

        ArquivoView enviado = gerenciar().enviarConteudo(maria, atendimento, imagem.id(), "foto do celular.jpg", corpo(JPEG),
                JPEG.length);

        assertThat(enviado).extracting(ArquivoView::situacao, ArquivoView::tamanho, ArquivoView::erro)
                .containsExactly("pronto", (long) JPEG.length, null);
        assertThat(tarefas.ativas(atendimento)).as("o download pendente foi cancelado").isZero();
        assertThat(arquivos.paraBaixar(imagem.id()).orElseThrow().url()).isNull();
        assertThat(arquivos.buscar(atendimento, imagem.id()).orElseThrow().caminho()).contains("anexo-manual_001_");
        recusado(() -> gerenciar().enviarConteudo(maria, atendimento, enviar("print_extra", "p.png", PNG).id(), null,
                corpo(PNG), PNG.length), "SO_ANEXOS_DA_CONVERSA");
    }

    @Test
    void tirarUmExtraEUmAnexo() throws IOException {
        ArquivoView print = enviar("print_extra", "p.png", PNG);
        ArquivoDoAtendimento anexo = arquivos.doAtendimento(atendimento).get(1);
        tarefas.criar(TipoDeTarefa.BAIXAR, atendimento, anexo.id(), null, 3, AGORA);

        gerenciar().tirar(maria, atendimento, print.id());
        gerenciar().tirar(maria, atendimento, anexo.id());

        assertThat(arquivos.buscar(atendimento, print.id())).isEmpty();
        assertThat(arquivosNaPasta()).isZero();
        assertThat(arquivos.buscar(atendimento, anexo.id()).orElseThrow().situacao()).isEqualTo("removido");
        assertThat(tarefas.ativas(atendimento)).isZero();
        recusado(() -> gerenciar().tirar(maria, atendimento, print.id()), "ARQUIVO_NAO_ENCONTRADO");
        recusado(() -> gerenciar().tirar(joao, atendimento, anexo.id()), "ATENDIMENTO_NAO_ENCONTRADO");
    }

    @Test
    void depoisDoEnvioAMaoOAtendimentoQueProcessavaFicaPronto() {
        ProcessarAtendimentos processamento = new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio, 3);
        processamento.processar(maria, atendimento);
        assertThat(atendimentos.estado(atendimento).orElseThrow().situacao()).isEqualTo(SituacaoDoAtendimento.PROCESSANDO);

        for (ArquivoDoAtendimento a : arquivos.doAtendimento(atendimento)) {
            gerenciar().enviarConteudo(maria, atendimento, a.id(), "a.jpg", corpo(JPEG), JPEG.length);
        }

        assertThat(atendimentos.estado(atendimento).orElseThrow().situacao()).isEqualTo(SituacaoDoAtendimento.PRONTO);
    }

    private static Usuario usuario(UUID id, String login) {
        return new Usuario(id, login, login, "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
    }
}
