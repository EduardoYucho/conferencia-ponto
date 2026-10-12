package br.com.conferenciaponto.modulos.atendimento.infrastructure.video;

import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Os quadros pelo ffmpeg: o programa de mentira (sempre) e o ffmpeg de verdade (quando estiver instalado). */
class ExtratorQuadrosFfmpegTest {

    @TempDir
    Path pasta;

    private Path video() throws IOException {
        Path video = pasta.resolve("video.mp4");
        Files.write(video, new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'});
        return video;
    }

    private static List<Path> temporarios(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(".quadro-")).toList();
        }
    }

    @Test
    void comOProgramaEncontradoTiraOQuadroNoInstantePedido() throws IOException {
        ExtratorQuadrosFfmpeg extrator = new ExtratorQuadrosFfmpeg(FfmpegDeMentira.comando(), Duration.ofSeconds(30));
        ExtratorQuadros.Situacao situacao = extrator.situacao();
        assertThat(situacao.disponivel()).isTrue();
        assertThat(situacao.versao()).isEqualTo("9.9-de-mentira");

        Path destino = pasta.resolve("quadros/v1/000135000.png");
        extrator.extrair(video(), 135_000, destino);

        BufferedImage imagem = ImageIO.read(destino.toFile());
        assertThat(imagem.getWidth()).isEqualTo(4);
        assertThat((imagem.getRGB(0, 0) >> 16) & 0xFF).as("a cor marca o segundo 135").isEqualTo(135);
        assertThat(temporarios(destino.getParent())).as("sem temporário sobrando").isEmpty();
    }

    @Test
    void semOProgramaOAvisoEFfmpegAusente() throws IOException {
        ExtratorQuadrosFfmpeg extrator = new ExtratorQuadrosFfmpeg(List.of(), Duration.ofSeconds(5));
        assertThat(extrator.situacao()).isEqualTo(ExtratorQuadros.Situacao.AUSENTE);
        Path destino = pasta.resolve("q.png");

        assertThatThrownBy(() -> extrator.extrair(video(), 1000, destino))
                .isInstanceOfSatisfying(FalhaNoQuadro.class, f -> assertThat(f.codigo()).isEqualTo("FFMPEG_AUSENTE"));
        assertThat(destino).doesNotExist();
    }

    @Test
    void programaQueNaoEOFfmpegContaComoAusente() {
        String java = FfmpegDeMentira.java();
        ExtratorQuadrosFfmpeg extrator = new ExtratorQuadrosFfmpeg(List.of(java, "-cp", System.getProperty("java.class.path"),
                "classe.que.NaoExiste"), Duration.ofSeconds(30));
        assertThat(extrator.situacao().disponivel()).isFalse();
    }

    @Test
    void quadroQueNaoSaiEFfmpegFalhouSemDeixarArquivo() throws IOException {
        ExtratorQuadrosFfmpeg extrator = new ExtratorQuadrosFfmpeg(FfmpegDeMentira.comando(), Duration.ofSeconds(30));
        Path destino = pasta.resolve("quadros/x.png");

        assertThatThrownBy(() -> extrator.extrair(video(), 1_200_000, destino))
                .isInstanceOfSatisfying(FalhaNoQuadro.class, f -> {
                    assertThat(f.codigo()).isEqualTo("FFMPEG_FALHOU");
                    assertThat(f.getMessage()).contains("20:00");
                });
        assertThat(destino).doesNotExist();
        assertThat(temporarios(destino.getParent())).isEmpty();
    }

    @Test
    void programaQuePassaDoTempoEInterrompido() throws IOException {
        ExtratorQuadrosFfmpeg extrator = new ExtratorQuadrosFfmpeg(FfmpegDeMentira.comando(), Duration.ofSeconds(3));
        Path destino = pasta.resolve("lento.png");
        long inicio = System.nanoTime();

        assertThatThrownBy(() -> extrator.extrair(video(), 777_000, destino))
                .isInstanceOfSatisfying(FalhaNoQuadro.class, f -> assertThat(f.codigo()).isEqualTo("FFMPEG_FALHOU"));
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - inicio)).isLessThan(20);
        assertThat(destino).doesNotExist();
    }

    @Test
    void caminhoConfiguradoQueNaoExisteNaoEncontraNada() {
        assertThat(ExtratorQuadrosFfmpeg.procurarExecutavel(pasta.resolve("nao-existe").toString())).isEmpty();
    }

    // ------------------------------------------------------------------------------- o ffmpeg de verdade

    @Test
    void ffmpegDeVerdadeTiraOQuadroNaResolucaoOriginal() throws IOException, InterruptedException {
        Optional<Path> ffmpeg = ExtratorQuadrosFfmpeg.procurarExecutavel("");
        assumeTrue(ffmpeg.isPresent(), "ffmpeg não instalado nesta máquina: teste pulado");
        Path video = pasta.resolve("teste.mp4");
        Process gerar = new ProcessBuilder(ffmpeg.get().toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                "-i", "testsrc=duration=3:size=320x240:rate=10", "-pix_fmt", "yuv420p", "-y", video.toString())
                .redirectErrorStream(true).start();
        assumeTrue(gerar.waitFor(60, TimeUnit.SECONDS) && gerar.exitValue() == 0, "o ffmpeg desta máquina não gera o vídeo de teste");

        ExtratorQuadrosFfmpeg extrator = new ExtratorQuadrosFfmpeg(List.of(ffmpeg.get().toString()), Duration.ofSeconds(60));
        assertThat(extrator.situacao().disponivel()).isTrue();
        Path destino = pasta.resolve("quadros/001500.png");
        extrator.extrair(video, 1500, destino);

        BufferedImage imagem = ImageIO.read(destino.toFile());
        assertThat(imagem.getWidth()).isEqualTo(320);
        assertThat(imagem.getHeight()).isEqualTo(240);

        assertThatThrownBy(() -> extrator.extrair(video, 30_000, pasta.resolve("depois-do-fim.png")))
                .isInstanceOfSatisfying(FalhaNoQuadro.class, f -> assertThat(f.codigo()).isEqualTo("FFMPEG_FALHOU"));
    }
}
