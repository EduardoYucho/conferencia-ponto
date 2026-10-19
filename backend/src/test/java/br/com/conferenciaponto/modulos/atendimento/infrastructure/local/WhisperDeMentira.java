package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.FfmpegDeMentira;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Um programa que faz as vezes do whisper-cli e do nvidia-smi nos testes (roda num processo à parte, como os de
 * verdade). Como whisper: grava {@code <-of>.json} com dois trechos (e um "[Música]", que o sistema descarta); o áudio
 * com "falha" no nome sai com erro. Como nvidia-smi ({@code --query-gpu}): imprime "12288, 10240".
 */
public final class WhisperDeMentira {

    private WhisperDeMentira() {
    }

    public static List<String> comando() {
        return List.of(FfmpegDeMentira.java(), "-cp", System.getProperty("java.class.path"), WhisperDeMentira.class.getName());
    }

    public static void main(String[] args) throws IOException {
        List<String> a = List.of(args);
        if (a.stream().anyMatch(x -> x.startsWith("--query-gpu"))) {
            System.out.println("12288, 10240");
            return;
        }
        Path audio = Path.of(a.get(a.indexOf("-f") + 1));
        if (!Files.isRegularFile(Path.of(a.get(a.indexOf("-m") + 1))) || audio.getFileName().toString().contains("falha")) {
            System.err.println("failed to process audio");
            System.exit(3);
        }
        String base = a.get(a.indexOf("-of") + 1);
        String idioma = a.get(a.indexOf("-l") + 1);
        Files.writeString(Path.of(base + ".json"), """
                {"systeminfo":"de mentira","model":{"type":"large"},"params":{"language":"%s"},"result":{"language":"pt"},
                 "transcription":[
                   {"timestamps":{"from":"00:00:00,000","to":"00:00:04,800"},"offsets":{"from":0,"to":4800},"text":" Oi, Eduardo."},
                   {"timestamps":{"from":"00:00:04,800","to":"00:00:06,000"},"offsets":{"from":4800,"to":6000},"text":" [Música]"},
                   {"timestamps":{"from":"00:01:05,360","to":"00:01:09,480"},"offsets":{"from":65360,"to":69480},"text":" A nota não sai."}
                 ]}""".formatted(idioma), StandardCharsets.UTF_8);
    }
}
