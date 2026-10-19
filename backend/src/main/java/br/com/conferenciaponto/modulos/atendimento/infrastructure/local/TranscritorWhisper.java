package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo.Tipo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A transcrição dos áudios pelo Whisper (o whisper-cli do whisper.cpp, com a placa de vídeo quando ela existe): o WAV
 * de 16 kHz entra, e a saída em JSON ({@code -oj}) traz cada trecho com o início em milissegundos.
 */
class TranscritorWhisper implements Transcritor {

    private static final Logger log = LoggerFactory.getLogger(TranscritorWhisper.class);

    private final List<String> comando;
    private final Path modelo;
    private final String idioma;
    private final Duration tempoLimite;
    private final ObjectMapper json;

    /**
     * @param comando o programa (e, nos testes, os argumentos que o antecedem)
     */
    TranscritorWhisper(List<String> comando, Path modelo, String idioma, Duration tempoLimite, ObjectMapper json) {
        this.comando = List.copyOf(comando);
        this.modelo = modelo;
        this.idioma = idioma == null || idioma.isBlank() ? "pt" : idioma.strip();
        this.tempoLimite = tempoLimite;
        this.json = json;
    }

    @Override
    public boolean disponivel() {
        return comando.size() > 1 || Files.isRegularFile(Path.of(comando.get(0)));
    }

    @Override
    public List<Trecho> transcrever(Path wav) {
        if (!disponivel()) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "o Whisper não está instalado em " + comando.get(0));
        }
        if (!Files.isRegularFile(modelo)) {
            throw new FalhaDoModelo(Tipo.MODELO_INEXISTENTE, "o modelo do Whisper não está em " + modelo);
        }
        Path base = wav.resolveSibling(nomeSemExtensao(wav) + "-transcricao");
        Path saida = base.resolveSibling(base.getFileName() + ".json");
        List<String> argumentos = new ArrayList<>(comando);
        argumentos.addAll(List.of("-m", modelo.toString(), "-f", wav.toString(), "-l", idioma, "-oj", "-of", base.toString(),
                "-np", "-t", String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))));
        Process processo = null;
        try {
            processo = new ProcessBuilder(argumentos).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!processo.waitFor(tempoLimite.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new FalhaDoModelo(Tipo.INDISPONIVEL, "o Whisper demorou mais que " + tempoLimite.toMinutes() + " min");
            }
            if (processo.exitValue() != 0 || !Files.isRegularFile(saida)) {
                log.warn("Whisper saiu com {} sem a transcrição", processo.exitValue());
                throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "o Whisper não conseguiu transcrever o áudio");
            }
            return lerTrechos(Files.readString(saida, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "não foi possível rodar o Whisper (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDoModelo(Tipo.INDISPONIVEL, "a transcrição foi interrompida");
        } finally {
            if (processo != null && processo.isAlive()) {
                processo.destroyForcibly();
            }
            apagar(saida);
        }
    }

    /** Os trechos do JSON do whisper-cli: {@code transcription[].offsets.from} (ms) e {@code .text}. */
    List<Trecho> lerTrechos(String conteudo) throws IOException {
        JsonNode lido = json.readTree(conteudo);
        List<Trecho> trechos = new ArrayList<>();
        for (JsonNode t : lido.path("transcription")) {
            String texto = t.path("text").asText("").strip();
            if (!texto.isEmpty() && !texto.matches("\\[[^\\]]*\\]|\\([^)]*\\)")) { // [Música], (risos)
                trechos.add(new Trecho(Math.max(0, t.path("offsets").path("from").asLong(0)), texto));
            }
        }
        return trechos;
    }

    private static String nomeSemExtensao(Path arquivo) {
        String nome = arquivo.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return ponto > 0 ? nome.substring(0, ponto) : nome;
    }

    private static void apagar(Path arquivo) {
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            // fica na pasta temporária
        }
    }
}
