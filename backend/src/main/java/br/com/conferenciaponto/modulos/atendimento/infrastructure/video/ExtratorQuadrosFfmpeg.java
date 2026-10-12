package br.com.conferenciaponto.modulos.atendimento.infrastructure.video;

import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import br.com.conferenciaponto.modulos.atendimento.domain.video.InstanteDoVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Os quadros dos vídeos pelo ffmpeg (opcional). O programa é procurado em {@code atendimento.ffmpeg.caminho} (o
 * executável ou a pasta dele) ou, com o campo vazio, no PATH e nos lugares onde o {@code winget install
 * Gyan.FFmpeg} o coloca. A versão é conferida na subida; sem o programa, quem pede um quadro recebe FFMPEG_AUSENTE e
 * a procura é refeita a cada minuto (instalar o ffmpeg não exige reiniciar o sistema).
 *
 * <p>Cada quadro é um PNG na resolução original, tirado no instante pedido ({@code -ss} antes do {@code -i}, que no
 * ffmpeg atual é exato), gravado num temporário e movido no fim. O log leva o código de saída e a última linha do
 * erro do ffmpeg, nunca o conteúdo do vídeo.
 */
@Component
public class ExtratorQuadrosFfmpeg implements ExtratorQuadros {

    private static final Logger log = LoggerFactory.getLogger(ExtratorQuadrosFfmpeg.class);
    private static final Pattern VERSAO = Pattern.compile("(?im)^ffmpeg version (\\S+)");
    private static final Duration NOVA_PROCURA = Duration.ofMinutes(1);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Supplier<Optional<List<String>>> procurar;
    private final Duration tempoLimite;
    private final Clock clock;

    private volatile Encontrado encontrado;
    private volatile Instant procuradoEm;

    /** O programa encontrado: o comando (o executável, ou o programa de teste com os argumentos dele) e a versão. */
    private record Encontrado(List<String> comando, String versao) {
    }

    @Autowired
    public ExtratorQuadrosFfmpeg(@Value("${atendimento.ffmpeg.caminho:}") String caminho,
                                 @Value("${atendimento.ffmpeg.tempo-limite:60s}") Duration tempoLimite) {
        this(() -> procurarExecutavel(caminho).map(p -> List.of(p.toString())), tempoLimite, Clock.systemUTC());
    }

    /** Para os testes: um comando fixo (ex.: um programa Java que faz as vezes do ffmpeg) ou nenhum (vazio). */
    public ExtratorQuadrosFfmpeg(List<String> comando, Duration tempoLimite) {
        this(() -> Optional.ofNullable(comando).filter(c -> !c.isEmpty()), tempoLimite, Clock.systemUTC());
    }

    ExtratorQuadrosFfmpeg(Supplier<Optional<List<String>>> procurar, Duration tempoLimite, Clock clock) {
        this.procurar = procurar;
        this.tempoLimite = tempoLimite;
        this.clock = clock;
    }

    /** Na subida: diz no log se os vídeos vão ter quadros. */
    @EventListener(ApplicationReadyEvent.class)
    public void conferirNaSubida() {
        Situacao s = situacao();
        if (s.disponivel()) {
            log.info("ffmpeg {} encontrado em {}: os passos dos vídeos ganham quadros", s.versao(), s.caminho());
        } else {
            log.warn("ffmpeg não encontrado: os vídeos são analisados, mas os passos ficam sem quadros. Para instalar: "
                    + "winget install Gyan.FFmpeg (ou informe atendimento.ffmpeg.caminho)");
        }
    }

    @Override
    public Situacao situacao() {
        Encontrado e = atual();
        return e == null ? Situacao.AUSENTE : new Situacao(true, e.versao(), e.comando().get(0));
    }

    @Override
    public void extrair(Path video, long instanteMs, Path destino) {
        Encontrado e = atual();
        if (e == null) {
            throw new FalhaNoQuadro(FalhaNoQuadro.FFMPEG_AUSENTE, "O ffmpeg não está instalado no servidor.");
        }
        Path temporario = null;
        try {
            Files.createDirectories(destino.getParent());
            temporario = Files.createTempFile(destino.getParent(), ".quadro-", ".png");
            List<String> comando = new ArrayList<>(e.comando());
            comando.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-ss", "%d.%03d".formatted(instanteMs / 1000, instanteMs % 1000),
                    "-i", video.toAbsolutePath().toString(),
                    "-frames:v", "1", "-an", "-f", "image2", "-c:v", "png", "-y", temporario.toAbsolutePath().toString()));
            Saida saida = rodar(comando, tempoLimite);
            if (saida.codigo() != 0) {
                log.warn("ffmpeg saiu com {} no quadro de {}: {}", saida.codigo(), InstanteDoVideo.texto(instanteMs),
                        saida.ultimaLinha());
                throw new FalhaNoQuadro(FalhaNoQuadro.FFMPEG_FALHOU, "O ffmpeg não conseguiu tirar o quadro de "
                        + InstanteDoVideo.texto(instanteMs) + ".");
            }
            if (!Files.isRegularFile(temporario) || Files.size(temporario) == 0) {
                throw new FalhaNoQuadro(FalhaNoQuadro.FFMPEG_FALHOU, "O vídeo não tem imagem em "
                        + InstanteDoVideo.texto(instanteMs) + " (o instante passa do fim?).");
            }
            mover(temporario, destino);
            temporario = null;
        } catch (IOException ex) {
            log.warn("Quadro de {} não gravado: {}", InstanteDoVideo.texto(instanteMs), ex.getClass().getSimpleName());
            throw new FalhaNoQuadro(FalhaNoQuadro.FFMPEG_FALHOU, "Não foi possível gravar o quadro de "
                    + InstanteDoVideo.texto(instanteMs) + " no servidor.");
        } finally {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException ignorada) {
                    // fica o temporário (começa com ponto e some na retenção)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- procura

    /** O programa encontrado, procurando de novo quando não estava (no máximo uma vez por minuto). */
    private Encontrado atual() {
        Encontrado e = encontrado;
        if (e != null) {
            return e;
        }
        Instant agora = clock.instant();
        Instant antes = procuradoEm;
        if (antes != null && agora.isBefore(antes.plus(NOVA_PROCURA))) {
            return null;
        }
        synchronized (this) {
            if (encontrado != null) {
                return encontrado;
            }
            procuradoEm = agora;
            encontrado = procurar.get().flatMap(this::versao).orElse(null);
            if (encontrado != null && antes != null) {
                log.info("ffmpeg {} encontrado em {}: os próximos quadros saem", encontrado.versao(), encontrado.comando().get(0));
            }
            return encontrado;
        }
    }

    /** "ffmpeg version 7.1-full_build-www.gyan.dev ..." no começo de uma linha do {@code -version} (a primeira, no ffmpeg). */
    private Optional<Encontrado> versao(List<String> comando) {
        List<String> pedido = new ArrayList<>(comando);
        pedido.add("-version");
        try {
            Saida saida = rodar(pedido, Duration.ofSeconds(15));
            Matcher m = VERSAO.matcher(saida.texto());
            if (saida.codigo() == 0 && m.find()) {
                return Optional.of(new Encontrado(List.copyOf(comando), m.group(1)));
            }
            log.warn("{} não respondeu como o ffmpeg (código {}): os vídeos ficam sem quadros", comando.get(0), saida.codigo());
        } catch (IOException e) {
            log.debug("ffmpeg não roda em {}: {}", comando.get(0), e.toString());
        }
        return Optional.empty();
    }

    /** O executável configurado ou, sem configuração, o do PATH e os do winget. */
    static Optional<Path> procurarExecutavel(String configurado) {
        String nome = WINDOWS ? "ffmpeg.exe" : "ffmpeg";
        List<Path> candidatos = new ArrayList<>();
        if (configurado != null && !configurado.isBlank()) {
            Path p = Path.of(configurado.strip().replace("\"", ""));
            candidatos.add(Files.isDirectory(p) ? p.resolve(nome) : p);
            if (Files.isDirectory(p)) {
                candidatos.add(p.resolve("bin").resolve(nome));
            }
        } else {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String pasta : path.split(java.io.File.pathSeparator)) {
                    if (!pasta.isBlank()) {
                        try {
                            candidatos.add(Path.of(pasta.strip().replace("\"", "")).resolve(nome));
                        } catch (RuntimeException ignorada) {
                            // entrada do PATH que não é um caminho
                        }
                    }
                }
            }
            if (WINDOWS) {
                String local = System.getenv("LOCALAPPDATA");
                if (local != null) {
                    Path winget = Path.of(local, "Microsoft", "WinGet");
                    candidatos.add(winget.resolve("Links").resolve(nome));
                    candidatos.addAll(dosPacotesDoWinget(winget.resolve("Packages"), nome));
                }
                String programas = System.getenv("ProgramFiles");
                if (programas != null) {
                    candidatos.add(Path.of(programas, "ffmpeg", "bin", nome));
                }
                candidatos.add(Path.of("C:\\ffmpeg\\bin", nome));
            }
        }
        return candidatos.stream().filter(Files::isRegularFile).findFirst();
    }

    /** {@code Packages\Gyan.FFmpeg_...\ffmpeg-7.1-full_build\bin\ffmpeg.exe}. */
    private static List<Path> dosPacotesDoWinget(Path pacotes, String nome) {
        if (!Files.isDirectory(pacotes)) {
            return List.of();
        }
        try (Stream<Path> caminhos = Files.find(pacotes, 4, (c, a) -> a.isRegularFile() && c.getFileName().toString()
                .equalsIgnoreCase(nome) && c.toString().toLowerCase(Locale.ROOT).contains("ffmpeg"))) {
            return caminhos.sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------------------------------------- processo

    private record Saida(int codigo, String texto) {

        String ultimaLinha() {
            String t = texto.strip();
            String linha = t.substring(t.lastIndexOf('\n') + 1).strip();
            return linha.length() <= 200 ? linha : linha.substring(0, 200) + "…";
        }
    }

    /** Roda o programa com a saída (stdout e stderr juntos) limitada, e mata o processo que passar do tempo. */
    private static Saida rodar(List<String> comando, Duration limite) throws IOException {
        Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();
        processo.getOutputStream().close();
        StringBuilder texto = new StringBuilder();
        Thread leitor = new Thread(() -> ler(processo.getInputStream(), texto), "ffmpeg-saida");
        leitor.setDaemon(true);
        leitor.start();
        try {
            if (!processo.waitFor(limite.toMillis(), TimeUnit.MILLISECONDS)) {
                processo.destroyForcibly();
                processo.waitFor(5, TimeUnit.SECONDS);
                leitor.join(1000);
                return new Saida(-1, "o ffmpeg demorou mais de " + limite.toSeconds() + " s");
            }
            leitor.join(2000);
        } catch (InterruptedException e) {
            processo.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrompido", e);
        }
        synchronized (texto) {
            return new Saida(processo.exitValue(), texto.toString());
        }
    }

    private static void ler(InputStream entrada, StringBuilder texto) {
        byte[] buffer = new byte[8192];
        try (entrada) {
            int lidos;
            while ((lidos = entrada.read(buffer)) >= 0) {
                synchronized (texto) {
                    if (texto.length() < 64_000) {
                        texto.append(new String(buffer, 0, lidos, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException ignorada) {
            // o processo terminou
        }
    }

    private static void mover(Path origem, Path destino) throws IOException {
        try {
            Files.move(origem, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
