package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Quanto da placa de vídeo está em uso, pelo nvidia-smi (vem com o driver da NVIDIA). Sem o programa (outra placa, ou
 * sem placa), não dá para saber, e a IA local não espera.
 */
class MemoriaDaPlaca {

    private static final Logger log = LoggerFactory.getLogger(MemoriaDaPlaca.class);
    private static final long MIB = 1024L * 1024;

    private final List<String> comando;
    private volatile boolean avisouAusente;

    MemoriaDaPlaca(List<String> comando) {
        this.comando = List.copyOf(comando);
    }

    /** A memória da placa: o total e o que está em uso agora (por todos os programas, a IA local inclusive). */
    record Uso(long total, long usado) {
    }

    /** O uso agora (a primeira placa), ou vazio se não deu para saber. */
    java.util.Optional<Uso> agora() {
        List<String> argumentos = new ArrayList<>(comando);
        argumentos.addAll(List.of("--query-gpu=memory.total,memory.used", "--format=csv,noheader,nounits"));
        Process processo = null;
        try {
            processo = new ProcessBuilder(argumentos).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] saida = processo.getInputStream().readNBytes(4096);
            if (!processo.waitFor(15, TimeUnit.SECONDS) || processo.exitValue() != 0) {
                return java.util.Optional.empty();
            }
            String primeira = new String(saida, StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(l -> l.matches("\\d+\\s*,\\s*\\d+.*")).findFirst().orElse("");
            String[] partes = primeira.split(",");
            if (partes.length < 2) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Uso(Long.parseLong(partes[0].strip()) * MIB, Long.parseLong(partes[1].strip()) * MIB));
        } catch (IOException | NumberFormatException e) {
            if (!avisouAusente) {
                avisouAusente = true;
                log.info("IA local: não foi possível ler o uso da placa de vídeo (nvidia-smi: {}); a IA não espera a placa "
                        + "ficar livre", e.getClass().getSimpleName());
            }
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        } finally {
            if (processo != null && processo.isAlive()) {
                processo.destroyForcibly();
            }
        }
    }

    /**
     * Quanto da placa sobra para a IA local: o total menos o que os outros programas usam (o que a própria IA ocupa
     * conta como livre para ela).
     */
    OptionalLong livreParaAIa(long usadoPelaIa) {
        return agora().map(u -> OptionalLong.of(Math.max(0, u.total() - Math.max(0, u.usado() - usadoPelaIa))))
                .orElse(OptionalLong.empty());
    }
}
