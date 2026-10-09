package br.com.conferenciaponto.modulos.atendimento.application.chave;

import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave.Resultado;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave.Tipo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarChaveGeminiTest {

    private static final String CHAVE = "AIzaSyD-chave-de-teste_0123456789abcd";
    private static final Instant AGORA = Instant.parse("2026-10-09T18:00:00Z");

    @TempDir
    Path pasta;

    private final ChavesEmMemoria chaves = new ChavesEmMemoria();
    private final GoogleDeMentira google = new GoogleDeMentira();
    private CofreAesGcm cofre;
    private GerenciarChaveGemini gerenciar;
    private final Usuario maria = usuario("maria");

    @BeforeEach
    void preparar() {
        cofre = new CofreAesGcm(new ChaveMestraProperties(pasta.resolve("chave-mestra")));
        gerenciar = servico(true);
    }

    private GerenciarChaveGemini servico(boolean exigirNivelPago) {
        return new GerenciarChaveGemini(chaves, cofre, google, Clock.fixed(AGORA, ZoneOffset.UTC), exigirNivelPago);
    }

    @Test
    void semChaveCadastrada() {
        ChaveGeminiView estado = gerenciar.estado(maria);

        assertThat(estado.cadastrada()).isFalse();
        assertThat(estado.exigirNivelPago()).isTrue();
    }

    @Test
    void chaveAceitaPeloGoogleFicaGuardadaCifrada() {
        ChaveGeminiView view = gerenciar.cadastrar(maria, "  " + CHAVE + "\n", true);

        assertThat(google.chavesTestadas).containsExactly(CHAVE);
        assertThat(view.cadastrada()).isTrue();
        assertThat(view.ultimosCaracteres()).isEqualTo("abcd");
        assertThat(view.situacao()).isEqualTo("valida");
        assertThat(view.mensagem()).contains("aceitou");
        ChaveGemini guardada = chaves.porUsuario.get(maria.id());
        assertThat(new String(guardada.cifrada().cifrada(), StandardCharsets.ISO_8859_1)).doesNotContain(CHAVE);
        assertThat(cofre.decifrar(guardada.cifrada(), maria.id())).isEqualTo(CHAVE);
        assertThat(guardada.testadaEm()).isEqualTo(AGORA);
        assertThat(gerenciar.estado(maria).situacao()).isEqualTo("valida");
    }

    @Test
    void chaveSemCotaTambemEGuardada() {
        google.resposta = new Resultado(Tipo.SEM_COTA, "Renova à meia-noite do Pacífico.");

        ChaveGeminiView view = gerenciar.cadastrar(maria, CHAVE, true);

        assertThat(view.situacao()).isEqualTo("sem_cota");
        assertThat(view.mensagem()).contains("cota");
        assertThat(chaves.porUsuario).containsKey(maria.id());
    }

    @Test
    void chaveRecusadaNaoEGuardada() {
        google.resposta = new Resultado(Tipo.RECUSADA, "A chave é inválida, expirou ou foi apagada.");

        assertCodigo(() -> gerenciar.cadastrar(maria, CHAVE, true), "CHAVE_GEMINI_RECUSADA", "não foi guardada");
        assertThat(chaves.porUsuario).isEmpty();
    }

    @Test
    void googleForaDoArOuSemInternetNaoGuardam() {
        google.resposta = new Resultado(Tipo.INDISPONIVEL, "O Google não respondeu a tempo.");
        assertCodigo(() -> gerenciar.cadastrar(maria, CHAVE, true), "GEMINI_INDISPONIVEL", "não foi guardada");

        google.resposta = new Resultado(Tipo.SEM_INTERNET, null);
        assertCodigo(() -> gerenciar.cadastrar(maria, CHAVE, true), "SEM_INTERNET", "sem internet");

        assertThat(chaves.porUsuario).isEmpty();
    }

    @Test
    void recusaAntesDePerguntarAoGoogle() {
        assertCodigo(() -> gerenciar.cadastrar(maria, "   ", true), "CHAVE_GEMINI_VAZIA", "Cole a chave");
        assertCodigo(() -> gerenciar.cadastrar(maria, "AIza chave com espaço no meio", true), "CHAVE_GEMINI_FORMATO", "AIza");
        assertCodigo(() -> gerenciar.cadastrar(maria, "curta", true), "CHAVE_GEMINI_FORMATO", "AIza");
        assertCodigo(() -> gerenciar.cadastrar(maria, CHAVE, false), "NIVEL_PAGO_NAO_CONFIRMADO", "nível pago");

        assertThat(google.chavesTestadas).isEmpty();
    }

    @Test
    void semAExigenciaDoNivelPagoAceitaSemConfirmacao() {
        ChaveGeminiView view = servico(false).cadastrar(maria, CHAVE, false);

        assertThat(view.cadastrada()).isTrue();
        assertThat(view.nivelPagoConfirmado()).isFalse();
    }

    @Test
    void testarDeNovoAtualizaASituacao() {
        gerenciar.cadastrar(maria, CHAVE, true);
        google.resposta = new Resultado(Tipo.RECUSADA, "A chave é inválida, expirou ou foi apagada.");

        ChaveGeminiView view = gerenciar.testar(maria);

        assertThat(view.situacao()).isEqualTo("recusada");
        assertThat(view.mensagem()).contains("recusou");
        assertThat(chaves.porUsuario.get(maria.id()).situacao()).isEqualTo(SituacaoDaChave.RECUSADA);
        assertThat(google.chavesTestadas).containsExactly(CHAVE, CHAVE);
    }

    @Test
    void testarSemGoogleNaoMudaASituacao() {
        gerenciar.cadastrar(maria, CHAVE, true);
        google.resposta = new Resultado(Tipo.SEM_INTERNET, null);

        assertCodigo(() -> gerenciar.testar(maria), "SEM_INTERNET", "sem internet");
        assertThat(chaves.porUsuario.get(maria.id()).situacao()).isEqualTo(SituacaoDaChave.VALIDA);
    }

    @Test
    void testarSemChave() {
        assertCodigo(() -> gerenciar.testar(maria), "SEM_CHAVE_GEMINI", "ainda não cadastrou");
    }

    @Test
    void chaveMestraTrocadaPedeParaCadastrarDeNovo() throws Exception {
        gerenciar.cadastrar(maria, CHAVE, true);
        Files.delete(pasta.resolve("chave-mestra"));
        cofre = new CofreAesGcm(new ChaveMestraProperties(pasta.resolve("chave-mestra")));

        assertCodigo(() -> servico(true).testar(maria), "CHAVE_MESTRA_TROCADA", "Cadastre a sua chave de novo");
    }

    @Test
    void apagar() {
        gerenciar.cadastrar(maria, CHAVE, true);

        ChaveGeminiView view = gerenciar.apagar(maria);

        assertThat(view.cadastrada()).isFalse();
        assertThat(chaves.porUsuario).isEmpty();
    }

    @Test
    void aChaveNuncaVaiParaOLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> linhas = new ListAppender<>();
        linhas.start();
        logger.addAppender(linhas);
        try {
            gerenciar.cadastrar(maria, CHAVE, true);
            gerenciar.testar(maria);
            google.resposta = new Resultado(Tipo.RECUSADA, "A chave é inválida.");
            try {
                gerenciar.cadastrar(maria, CHAVE, true);
            } catch (DominioException esperada) {
                assertThat(esperada.getMessage()).doesNotContain(CHAVE);
            }
            gerenciar.apagar(maria);
        } finally {
            logger.detachAppender(linhas);
        }

        assertThat(linhas.list).isNotEmpty();
        assertThat(linhas.list).allSatisfy(l -> assertThat(l.getFormattedMessage()).doesNotContain(CHAVE).doesNotContain("abcd"));
    }

    // --------------------------------------------------------------------------------------------- apoio

    private static void assertCodigo(org.assertj.core.api.ThrowableAssert.ThrowingCallable acao, String codigo, String trecho) {
        assertThatThrownBy(acao)
                .isInstanceOf(DominioException.class)
                .hasMessageContaining(trecho)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(CHAVE))
                .extracting("codigo").isEqualTo(codigo);
    }

    private static Usuario usuario(String login) {
        return new Usuario(UUID.randomUUID(), login, login, "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
    }

    static class ChavesEmMemoria implements ChavesGemini {
        final Map<UUID, ChaveGemini> porUsuario = new HashMap<>();

        @Override
        public Optional<ChaveGemini> buscar(UUID usuarioId) {
            return Optional.ofNullable(porUsuario.get(usuarioId));
        }

        @Override
        public void salvar(ChaveGemini chave) {
            porUsuario.put(chave.usuarioId(), chave);
        }

        @Override
        public void atualizarSituacao(UUID usuarioId, SituacaoDaChave situacao, Instant testadaEm) {
            porUsuario.computeIfPresent(usuarioId, (id, c) -> c.comSituacao(situacao, testadaEm));
        }

        @Override
        public void apagar(UUID usuarioId) {
            porUsuario.remove(usuarioId);
        }
    }

    static class GoogleDeMentira implements VerificadorDeChave {
        Resultado resposta = new Resultado(Tipo.VALIDA, null);
        final java.util.List<String> chavesTestadas = new java.util.ArrayList<>();

        @Override
        public Resultado testar(String chave) {
            chavesTestadas.add(chave);
            return resposta;
        }
    }
}
