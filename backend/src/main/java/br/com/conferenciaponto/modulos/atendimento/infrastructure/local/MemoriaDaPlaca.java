package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * Quanto da placa de vídeo está em uso, pelo nvidia-smi (vem com o driver da NVIDIA): a memória e o quanto ela está
 * trabalhando. O nvidia-smi não diz a memória de cada programa no Windows, e o Ollama não informa direito a do modelo
 * dele; por isso a memória só vale com o modelo fora da placa, e com ele carregado vale o uso. Sem o programa (outra
 * placa, ou sem placa), não dá para saber, e a IA local não espera.
 */
class MemoriaDaPlaca {

    private static final Logger log = LoggerFactory.getLogger(MemoriaDaPlaca.class);
    private static final long MIB = 1024L * 1024;

    private final List<String> comando;
    private volatile boolean avisouAusente;

    MemoriaDaPlaca(List<String> comando) {
        this.comando = List.copyOf(comando);
    }

    /**
     * A placa agora: a memória total, a memória em uso (por todos os programas, a IA local inclusive) e o quanto ela
     * está trabalhando (0 a 100).
     */
    record Uso(long total, long usado, int utilizacao) {

        long livre() {
            return Math.max(0, total - usado);
        }
    }

    /** O uso agora (a primeira placa), ou vazio se não deu para saber. */
    java.util.Optional<Uso> agora() {
        List<String> argumentos = new ArrayList<>(comando);
        argumentos.addAll(List.of("--query-gpu=memory.total,memory.used,utilization.gpu", "--format=csv,noheader,nounits"));
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
            int utilizacao = partes.length > 2 && partes[2].strip().matches("\\d+") ? Integer.parseInt(partes[2].strip()) : 0;
            return java.util.Optional.of(new Uso(Long.parseLong(partes[0].strip()) * MIB,
                    Long.parseLong(partes[1].strip()) * MIB, utilizacao));
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
     * O menor uso da placa em algumas leituras seguidas (um pico passageiro, como uma janela abrindo, não conta): com
     * a IA parada, é o quanto os outros programas estão usando. Vazio se não deu para saber.
     */
    OptionalInt menorUtilizacao(int leituras, long intervaloMs) {
        int menor = Integer.MAX_VALUE;
        for (int i = 0; i < Math.max(1, leituras); i++) {
            if (i > 0) {
                try {
                    Thread.sleep(intervaloMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            java.util.Optional<Uso> uso = agora();
            if (uso.isEmpty()) {
                return OptionalInt.empty();
            }
            menor = Math.min(menor, uso.get().utilizacao());
        }
        return menor == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(menor);
    }
}
