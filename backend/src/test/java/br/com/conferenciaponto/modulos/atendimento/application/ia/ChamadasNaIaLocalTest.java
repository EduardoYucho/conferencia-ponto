package br.com.conferenciaponto.modulos.atendimento.application.ia;

import br.com.conferenciaponto.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ConfiguracaoDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.ConfiguracaoDosVetores;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Com a IA local: sem chave, as falhas dela (Ollama parado, placa ocupada) e os modelos que vêm da configuração. */
class ChamadasNaIaLocalTest {

    private final ChamadasAoGemini local = new ChamadasAoGemini(null, null, new ObjectMapper(), Clock.systemUTC(), true);

    @Test
    void naoPrecisaDeChave() {
        ChamadasAoGemini.ChaveDoDono dono = local.chaveDo(UUID.randomUUID());
        assertThat(dono.aberta()).isTrue();
        assertThat(dono.chave()).isEqualTo(ChamadasAoGemini.CHAVE_LOCAL);
        assertThat(dono.situacao()).isEqualTo(SituacaoDaChave.VALIDA);
        local.usoDeuCerto(UUID.randomUUID(), dono); // não mexe em chave nenhuma
    }

    @Test
    void placaOcupadaEsperaSemGastarTentativa() {
        ResultadoDaTarefa r = local.traduzir(new FalhaDoModelo(FalhaDoModelo.Tipo.PLACA_OCUPADA, "ocupada",
                Duration.ofMinutes(3), 0), UUID.randomUUID(), "gemma4:12b", "analisar o vídeo");
        assertThat(r).isInstanceOfSatisfying(ResultadoDaTarefa.FalhaTemporaria.class, t -> {
            assertThat(t.semGastarTentativa()).isTrue();
            assertThat(t.codigo()).isEqualTo("PLACA_OCUPADA");
            assertThat(t.esperar()).isEqualTo(Duration.ofMinutes(3));
            assertThat(t.mensagem()).contains("placa de vídeo");
        });
    }

    @Test
    void ollamaParadoTentaDeNovoEPausaComOQueFazer() {
        ResultadoDaTarefa r = local.traduzir(new FalhaDoModelo(FalhaDoModelo.Tipo.IA_LOCAL_PARADA, "parado"),
                UUID.randomUUID(), "gemma4:12b", "analisar o áudio");
        assertThat(r).isInstanceOfSatisfying(ResultadoDaTarefa.FalhaTemporaria.class, t -> {
            assertThat(t.semGastarTentativa()).isFalse();
            assertThat(t.aoEsgotar()).contains("Ollama").contains("Retomar").contains("analisar o áudio");
        });
    }

    @Test
    void modeloNaoInstaladoPausaEAsMensagensNaoFalamDeGeminiNemDeChave() {
        ResultadoDaTarefa sem = local.traduzir(new FalhaDoModelo(FalhaDoModelo.Tipo.MODELO_INEXISTENTE, "not found"),
                UUID.randomUUID(), "gemma4:12b", "redigir este texto");
        assertThat(sem).isInstanceOfSatisfying(ResultadoDaTarefa.Pausar.class,
                p -> assertThat(p.mensagem()).contains("gemma4:12b").contains("não está instalado"));
        for (FalhaDoModelo.Tipo tipo : FalhaDoModelo.Tipo.values()) {
            ResultadoDaTarefa r = local.traduzir(new FalhaDoModelo(tipo, "motivo"), UUID.randomUUID(), "gemma4:12b", "x");
            String mensagem = r instanceof ResultadoDaTarefa.FalhaTemporaria t ? t.mensagem() + " " + t.aoEsgotar()
                    : r instanceof ResultadoDaTarefa.FalhaDefinitiva f ? f.mensagem()
                    : r instanceof ResultadoDaTarefa.Pausar p ? p.mensagem() : "";
            assertThat(mensagem).as(tipo.name()).doesNotContain("Gemini").doesNotContain("chave");
        }
    }

    @Test
    void osModelosDaIaLocalVemDaConfiguracao() {
        ConfiguracaoDaAnalise analise = new ConfiguracaoDaAnalise("local", "gemma4:12b", "gemini-3.8-flash", "gemini-3.8-flash",
                5, DataSize.ofMegabytes(15), 32768, "0.7", "low", 2, DataSize.ofGigabytes(2), Duration.ofMinutes(15));
        assertThat(analise.usaGemini()).isFalse();
        assertThat(analise.identificacao(br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo.IMAGEM))
                .isEqualTo("gemma4:12b");
        assertThat(analise.identificacao(br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo.AUDIO))
                .isEqualTo("gemma4:12b + whisper");
        assertThat(analise.temperatura()).isNull();

        ConfiguracaoDaAnalise gemini = new ConfiguracaoDaAnalise("gemini", "gemma4:12b", "gemini-3.8-flash", "gemini-3.8-flash",
                5, DataSize.ofMegabytes(15), 32768, "", "", 1, DataSize.ofGigabytes(2), Duration.ofMinutes(15));
        assertThat(gemini.usaGemini()).isTrue();

        ConfiguracaoDaRedacao redacao = new ConfiguracaoDaRedacao("local", "gemma4:12b", "gemini-3.8-flash", 5, 32768, "", "",
                Duration.ofHours(1), DataSize.ofMegabytes(10));
        assertThat(redacao.modelo()).isEqualTo("gemma4:12b");

        ConfiguracaoDosVetores vetores = new ConfiguracaoDosVetores("local", "embeddinggemma", 768, "gemini-embedding-2", 1536,
                0.6, 30, 20, 5, 0.75);
        assertThat(vetores.modelo()).isEqualTo("embeddinggemma");
        assertThat(vetores.dimensao()).isEqualTo(768);
    }
}
