package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O Whisper e o nvidia-smi rodando como programas à parte (os de mentira, com a mesma Java dos testes). */
class TranscritorEPlacaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path pasta;

    private Path modelo() throws IOException {
        return Files.write(pasta.resolve("ggml-large-v3-turbo.bin"), new byte[]{1});
    }

    private Path wav(String nome) throws IOException {
        return Files.write(pasta.resolve(nome), new byte[]{1, 2, 3});
    }

    @Test
    void transcreveComOIdiomaEOInicioDeCadaTrechoSemOsMarcadoresDeSom() throws IOException {
        TranscritorWhisper whisper = new TranscritorWhisper(WhisperDeMentira.comando(), modelo(), "pt", Duration.ofSeconds(30), JSON);
        Path wav = wav("audio.wav");

        List<Transcritor.Trecho> trechos = whisper.transcrever(wav);

        assertThat(trechos).containsExactly(new Transcritor.Trecho(0, "Oi, Eduardo."),
                new Transcritor.Trecho(65_360, "A nota não sai."));
        assertThat(pasta.resolve("audio-transcricao.json")).as("a saída do Whisper é apagada").doesNotExist();
    }

    @Test
    void falhaDoWhisperFaltaDoProgramaOuDoModeloViramFalhasClaras() throws IOException {
        Path modelo = modelo();
        TranscritorWhisper whisper = new TranscritorWhisper(WhisperDeMentira.comando(), modelo, "pt", Duration.ofSeconds(30), JSON);
        assertThatThrownBy(() -> whisper.transcrever(wav("falha.wav")))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.ARQUIVO_FALHOU));

        TranscritorWhisper semPrograma = new TranscritorWhisper(List.of(pasta.resolve("nao-existe.exe").toString()), modelo, "pt",
                Duration.ofSeconds(30), JSON);
        assertThat(semPrograma.disponivel()).isFalse();
        assertThatThrownBy(() -> semPrograma.transcrever(wav("audio.wav")))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.getMessage()).contains("não está instalado"));

        TranscritorWhisper semModelo = new TranscritorWhisper(WhisperDeMentira.comando(), pasta.resolve("sem-modelo.bin"), "pt",
                Duration.ofSeconds(30), JSON);
        assertThatThrownBy(() -> semModelo.transcrever(wav("audio2.wav")))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.MODELO_INEXISTENTE));
    }

    @Test
    void oUsoDaPlacaVemDoNvidiaSmiESemEleNaoDaParaSaber() {
        MemoriaDaPlaca placa = new MemoriaDaPlaca(WhisperDeMentira.comando());
        assertThat(placa.agora()).hasValue(new MemoriaDaPlaca.Uso(12288L * 1024 * 1024, 10240L * 1024 * 1024, 35));
        assertThat(placa.menorUtilizacao(2, 10)).hasValue(35);

        MemoriaDaPlaca semPrograma = new MemoriaDaPlaca(List.of(pasta.resolve("nvidia-smi-que-nao-existe").toString()));
        assertThat(semPrograma.agora()).isEmpty();
        assertThat(semPrograma.menorUtilizacao(2, 10)).isEmpty();
    }
}
