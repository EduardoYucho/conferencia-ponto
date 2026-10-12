package br.com.conferenciaponto.modulos.atendimento.infrastructure.imagem;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.Marcacao;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MarcadorDeImagens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A borda vermelha numa cópia em PNG, em volta da área, sem tocar no original. */
class MarcadorJava2DTest {

    @TempDir
    Path pasta;

    private final MarcadorJava2D marcador = new MarcadorJava2D();

    private Path imagemCinza(String nome, String formato, int largura, int altura) throws Exception {
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < largura; x++) {
            for (int y = 0; y < altura; y++) {
                imagem.setRGB(x, y, new Color(200, 200, 200).getRGB());
            }
        }
        Path arquivo = pasta.resolve(nome);
        ImageIO.write(imagem, formato, arquivo.toFile());
        return arquivo;
    }

    private static boolean vermelho(BufferedImage imagem, int x, int y) {
        Color c = new Color(imagem.getRGB(x, y));
        return c.getRed() > 200 && c.getGreen() < 40 && c.getBlue() < 40;
    }

    @Test
    void desenhaABordaEmVoltaDaAreaNumaCopia() throws Exception {
        Path original = imagemCinza("print.png", "png", 400, 300);
        byte[] antes = Files.readAllBytes(original);
        Path destino = pasta.resolve("marcadas/1.png");

        // de x=100 a x=200 e de y=90 a y=150
        MarcadorDeImagens.Tamanho tamanho = marcador.marcar(original, destino,
                List.of(new Marcacao("mensagem de erro", 300, 250, 500, 500)));

        assertThat(tamanho).isEqualTo(new MarcadorDeImagens.Tamanho(400, 300));
        assertThat(Files.readAllBytes(original)).isEqualTo(antes);
        BufferedImage marcada = ImageIO.read(destino.toFile());
        assertThat(marcada.getWidth()).isEqualTo(400);
        assertThat(marcada.getHeight()).isEqualTo(300);
        // a borda fica uns pixels para fora da área (folga), e o que está dentro não muda
        int folga = MarcadorJava2D.FOLGA + 1;
        assertThat(vermelho(marcada, 150, 90 - folga)).as("borda de cima").isTrue();
        assertThat(vermelho(marcada, 150, 150 + folga)).as("borda de baixo").isTrue();
        assertThat(vermelho(marcada, 100 - folga, 120)).as("borda da esquerda").isTrue();
        assertThat(vermelho(marcada, 200 + folga, 120)).as("borda da direita").isTrue();
        assertThat(vermelho(marcada, 150, 120)).as("o meio da área").isFalse();
        assertThat(vermelho(marcada, 101, 91)).as("o canto de dentro").isFalse();
        assertThat(vermelho(marcada, 20, 20)).as("longe da área").isFalse();
        assertThat(pasta.resolve("marcadas")).isDirectoryContaining(p -> p.getFileName().toString().equals("1.png"));
        assertThat(Files.list(pasta.resolve("marcadas")).count()).as("sem temporário sobrando").isEqualTo(1);
    }

    @Test
    void jpegViraPngEAsCaixasInvalidasFicamDeFora() throws Exception {
        Path original = imagemCinza("foto.jpeg", "jpeg", 200, 100);
        Path destino = pasta.resolve("marcadas/2.png");

        marcador.marcar(original, destino, List.of(new Marcacao("invertida", 900, 900, 100, 100)));

        assertThat(Files.readAllBytes(destino)).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage marcada = ImageIO.read(destino.toFile());
        for (int x = 0; x < 200; x += 7) {
            for (int y = 0; y < 100; y += 7) {
                assertThat(vermelho(marcada, x, y)).isFalse();
            }
        }
    }

    @Test
    void marcarDeNovoTrocaACopia() throws Exception {
        Path original = imagemCinza("print.png", "png", 400, 300);
        Path destino = pasta.resolve("marcadas/1.png");
        marcador.marcar(original, destino, List.of(new Marcacao("a", 0, 0, 100, 100)));

        marcador.marcar(original, destino, List.of(new Marcacao("b", 800, 800, 900, 900)));

        BufferedImage marcada = ImageIO.read(destino.toFile());
        assertThat(vermelho(marcada, 20, 30 + MarcadorJava2D.FOLGA + 1)).as("a marcação antiga sumiu").isFalse();
    }

    @Test
    void formatoQueOServidorNaoAbreVemComMensagem() throws Exception {
        Path webp = pasta.resolve("tela.webp");
        Files.write(webp, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});

        assertThatThrownBy(() -> marcador.marcar(webp, pasta.resolve("marcadas/3.png"), List.of(new Marcacao("a", 0, 0, 10, 10))))
                .isInstanceOf(MarcadorDeImagens.FalhaNaMarcacao.class)
                .hasMessageContaining("só PNG, JPEG, GIF e BMP");
        assertThat(pasta.resolve("marcadas/3.png")).doesNotExist();
    }
}
