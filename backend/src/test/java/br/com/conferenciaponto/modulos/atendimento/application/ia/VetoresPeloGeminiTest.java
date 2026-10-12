package br.com.conferenciaponto.modulos.atendimento.application.ia;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import br.com.conferenciaponto.modulos.atendimento.application.evento.FalhaNoVetor;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Os vetores que o gerador oferece à base: com a chave da pessoa, e cada falha traduzida para a base. */
class VetoresPeloGeminiTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");
    private static final String CHAVE = "AQ.chave-de-teste-dos-vetores-wxyz";

    @TempDir
    Path raiz;

    private final ChavesGemini chaves = RepositoriosDeTeste.chaves();
    private final ModeloDeMentira modelo = new ModeloDeMentira();
    private VetoresPeloGemini vetores;
    private CofreAesGcm cofre;

    @BeforeEach
    void preparar() {
        cofre = new CofreAesGcm(new ChaveMestraProperties(raiz.resolve("chave-mestra")));
        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        vetores = new VetoresPeloGemini(new ChamadasAoGemini(chaves, cofre, new ObjectMapper(), relogio), chaves, modelo, relogio);
    }

    private UUID comChave() {
        UUID usuario = PostgresDeTeste.novoUsuario(true);
        chaves.salvar(new ChaveGemini(usuario, cofre.cifrar(CHAVE, usuario), "wxyz", true, SituacaoDaChave.NAO_TESTADA, AGORA, AGORA));
        return usuario;
    }

    @Test
    void calculaComAChaveDaPessoaEMarcaAChaveComoValida() {
        UUID usuario = comChave();

        float[] vetor = vetores.calcular(usuario, "gemini-embedding-2", 16, "title: Nota duplicada | text: ao emitir");

        assertThat(vetor).hasSize(16);
        assertThat(modelo.vetoresPedidos()).singleElement().satisfies(p -> {
            assertThat(p.chave()).isEqualTo(CHAVE);
            assertThat(p.modelo()).isEqualTo("gemini-embedding-2");
            assertThat(p.dimensao()).isEqualTo(16);
        });
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.VALIDA);
    }

    @Test
    void semChaveNaoChamaOGemini() {
        assertThatThrownBy(() -> vetores.calcular(PostgresDeTeste.novoUsuario(true), "gemini-embedding-2", 16, "texto"))
                .isInstanceOf(FalhaNoVetor.class)
                .hasMessageContaining("Cadastre sua chave do Gemini")
                .extracting("motivo").isEqualTo(FalhaNoVetor.Motivo.SEM_CHAVE);
        assertThat(modelo.vetoresPedidos()).isEmpty();
    }

    @Test
    void falhasDoGeminiTraduzidas() {
        UUID usuario = comChave();
        modelo.falharVetor(new FalhaDoModelo(FalhaDoModelo.Tipo.LIMITE, "limite", Duration.ofSeconds(7), 429))
                .falharVetor(new FalhaDoModelo(FalhaDoModelo.Tipo.COTA_DIARIA, "cota"))
                .falharVetor(new FalhaDoModelo(FalhaDoModelo.Tipo.SEM_INTERNET, "sem internet"))
                .falharVetor(new FalhaDoModelo(FalhaDoModelo.Tipo.CHAVE_RECUSADA, "Sua chave do Gemini foi recusada."));

        FalhaNoVetor limite = falha(usuario);
        assertThat(limite.motivo()).isEqualTo(FalhaNoVetor.Motivo.LIMITE);
        assertThat(limite.esperar()).isEqualTo(Duration.ofSeconds(7));
        assertThat(limite.passageira()).isTrue();
        assertThat(falha(usuario).motivo()).isEqualTo(FalhaNoVetor.Motivo.COTA_DIARIA);
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.SEM_COTA);
        assertThat(falha(usuario).motivo()).isEqualTo(FalhaNoVetor.Motivo.INDISPONIVEL);
        FalhaNoVetor recusada = falha(usuario);
        assertThat(recusada.motivo()).isEqualTo(FalhaNoVetor.Motivo.CHAVE_RECUSADA);
        assertThat(recusada.passageira()).isFalse();
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.RECUSADA);
    }

    private FalhaNoVetor falha(UUID usuario) {
        try {
            vetores.calcular(usuario, "gemini-embedding-2", 16, "texto");
        } catch (FalhaNoVetor f) {
            return f;
        }
        throw new AssertionError("era para falhar");
    }
}
