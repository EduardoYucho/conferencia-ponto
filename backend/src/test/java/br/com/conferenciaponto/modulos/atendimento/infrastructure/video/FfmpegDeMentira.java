package br.com.conferenciaponto.modulos.atendimento.infrastructure.video;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Um programa que faz as vezes do ffmpeg nos testes (roda num processo à parte, como o de verdade): responde ao
 * {@code -version} e, no pedido de quadro, grava um PNG de 4x3 cuja cor marca o instante (o vermelho é o segundo).
 * Instante a partir de 1000 s falha como vídeo sem imagem ali; 777 s demora (para o tempo limite).
 */
public final class FfmpegDeMentira {

    private FfmpegDeMentira() {
    }

    /** O executável da mesma Java dos testes. */
    public static String java() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    /** O comando para rodar este programa com a mesma Java dos testes. */
    public static List<String> comando() {
        String java = java();
        return List.of(java, "-cp", System.getProperty("java.class.path"), FfmpegDeMentira.class.getName());
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        List<String> a = List.of(args);
        if (a.contains("-version")) {
            System.out.println("ffmpeg version 9.9-de-mentira Copyright (c) os testes");
            System.out.println("configuration: --de-mentira");
            return;
        }
        int ss = a.indexOf("-ss");
        int entrada = a.indexOf("-i");
        if (ss < 0 || entrada < 0 || a.size() < 2) {
            System.err.println("Argumentos que o ffmpeg de mentira não entende");
            System.exit(8);
        }
        double segundos = Double.parseDouble(a.get(ss + 1));
        Path video = Path.of(a.get(entrada + 1));
        if (!Files.isRegularFile(video)) {
            System.err.println(video + ": No such file or directory");
            System.exit(1);
        }
        if (segundos >= 1000) {
            System.err.println("Output file is empty, nothing was encoded");
            System.exit(1);
        }
        if (segundos == 777) {
            Thread.sleep(60_000);
        }
        BufferedImage imagem = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        Color cor = new Color(((int) segundos) % 256, 10, 20);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 3; y++) {
                imagem.setRGB(x, y, cor.getRGB());
            }
        }
        ImageIO.write(imagem, "png", Path.of(a.get(a.size() - 1)).toFile());
    }
}
