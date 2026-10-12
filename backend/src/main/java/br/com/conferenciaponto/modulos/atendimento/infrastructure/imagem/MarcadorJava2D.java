package br.com.conferenciaponto.modulos.atendimento.infrastructure.imagem;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.Marcacao;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MarcadorDeImagens;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;

/**
 * As marcações com o Java2D: uma borda vermelha de 3 px (4 px nas imagens com mais de 1600 px de largura) em volta de
 * cada área, com uma folga de 4 px. A cópia sai em PNG, com o mesmo tamanho do original; o original não é tocado.
 * Lê PNG, JPEG, GIF e BMP (o que o Java lê sem bibliotecas a mais); WebP e HEIC não dão para marcar.
 */
@Component
public class MarcadorJava2D implements MarcadorDeImagens {

    static final Color VERMELHO = new Color(230, 0, 0);
    static final int FOLGA = 4;

    /** Acima disso (em pixels) a imagem não é aberta: ocuparia memória demais no servidor. */
    static final long PIXELS_NO_MAXIMO = 40_000_000L;

    @Override
    public Tamanho marcar(Path origem, Path destino, List<Marcacao> marcacoes) {
        conferirTamanho(origem);
        BufferedImage lida;
        try {
            lida = ImageIO.read(origem.toFile());
        } catch (IOException e) {
            throw new FalhaNaMarcacao("Não foi possível abrir a imagem para marcar (arquivo corrompido?).");
        }
        if (lida == null) {
            throw new FalhaNaMarcacao("Este formato de imagem não dá para marcar no servidor (só PNG, JPEG, GIF e BMP).");
        }
        int largura = lida.getWidth();
        int altura = lida.getHeight();
        BufferedImage copia = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copia.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, largura, altura); // fundo branco onde a imagem tinha transparência
            g.drawImage(lida, 0, 0, null);
            int espessura = largura > 1600 ? 4 : 3;
            g.setColor(VERMELHO);
            g.setStroke(new BasicStroke(espessura, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            for (Marcacao m : marcacoes) {
                if (!m.valida()) {
                    continue;
                }
                Marcacao.Retangulo r = m.emPixels(largura, altura, FOLGA + espessura / 2);
                g.drawRect(r.x(), r.y(), r.largura(), r.altura());
            }
        } finally {
            g.dispose();
        }
        try {
            Files.createDirectories(destino.getParent());
            Path temporario = Files.createTempFile(destino.getParent(), ".marcando-", ".png");
            try {
                if (!ImageIO.write(copia, "png", temporario.toFile())) {
                    throw new FalhaNaMarcacao("Não foi possível gravar a imagem marcada.");
                }
                try {
                    Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporario);
            }
        } catch (IOException e) {
            throw new FalhaNaMarcacao("Não foi possível gravar a imagem marcada (o disco pode estar cheio).");
        }
        return new Tamanho(largura, altura);
    }

    /** Lê só o cabeçalho da imagem: uma imagem enorme é recusada antes de ser aberta. */
    private static void conferirTamanho(Path origem) {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(origem.toFile())) {
            if (entrada == null) {
                return;
            }
            Iterator<ImageReader> leitores = ImageIO.getImageReaders(entrada);
            if (!leitores.hasNext()) {
                return; // o ImageIO.read diz que o formato não serve
            }
            ImageReader leitor = leitores.next();
            try {
                leitor.setInput(entrada, true, true);
                if ((long) leitor.getWidth(0) * leitor.getHeight(0) > PIXELS_NO_MAXIMO) {
                    throw new FalhaNaMarcacao("A imagem é grande demais para marcar no servidor.");
                }
            } finally {
                leitor.dispose();
            }
        } catch (IOException e) {
            throw new FalhaNaMarcacao("Não foi possível abrir a imagem para marcar (arquivo corrompido?).");
        }
    }
}
