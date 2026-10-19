package br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf;

import br.com.conferenciaponto.modulos.atendimento.domain.pacote.JuntadorDePdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * O PDF das imagens e dos documentos pelo PDFBox: cada imagem numa página A4 com o rótulo no alto, cada PDF depois
 * de uma página com o rótulo. O que não abre (formato que o PDFBox não lê, PDF com senha) fica de fora; o resto entra.
 */
@Component
public class JuntadorPdfBox implements JuntadorDePdf {

    private static final Logger log = LoggerFactory.getLogger(JuntadorPdfBox.class);
    private static final float MARGEM = 36;
    private static final float ALTURA_DO_ROTULO = 28;
    private static final int MAXIMO_DO_ROTULO = 110;
    /** Imagem pequena pode crescer um pouco para ficar legível, não mais que isso. */
    private static final float AMPLIACAO_MAXIMA = 1.5f;

    @Override
    public Resultado juntar(List<Item> itens, Path destino) {
        List<Integer> paginas = new ArrayList<>();
        List<PDDocument> abertos = new ArrayList<>();
        try (PDDocument pdf = new PDDocument()) {
            PDType1Font fonte = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (Item item : itens) {
                int primeira = pdf.getNumberOfPages() + 1;
                boolean entrou = item.pdf() ? documento(pdf, fonte, item, abertos) : imagem(pdf, fonte, item);
                paginas.add(entrou ? primeira : 0);
            }
            int total = pdf.getNumberOfPages();
            if (total > 0) {
                Files.createDirectories(destino.getParent());
                Path temporario = Files.createTempFile(destino.getParent(), ".pdf-", ".tmp");
                try {
                    pdf.save(temporario.toFile());
                    mover(temporario, destino);
                } finally {
                    Files.deleteIfExists(temporario);
                }
            }
            return new Resultado(List.copyOf(paginas), total);
        } catch (IOException e) {
            log.warn("Não foi possível gravar o PDF do pedido ao app do Gemini: {}", e.getClass().getSimpleName());
            return new Resultado(itens.stream().map(i -> 0).toList(), 0);
        } finally {
            for (PDDocument aberto : abertos) {
                try {
                    aberto.close();
                } catch (IOException ignorada) {
                    // só leitura
                }
            }
        }
    }

    private boolean imagem(PDDocument pdf, PDType1Font fonte, Item item) {
        PDImageXObject imagem;
        try {
            imagem = PDImageXObject.createFromFileByContent(item.arquivo().toFile(), pdf);
        } catch (IOException | IllegalArgumentException e) {
            return false; // formato que o PDFBox não lê (ex.: WebP, HEIC)
        }
        PDPage pagina = new PDPage(PDRectangle.A4);
        pdf.addPage(pagina);
        float largura = pagina.getMediaBox().getWidth();
        float altura = pagina.getMediaBox().getHeight();
        float areaLargura = largura - 2 * MARGEM;
        float areaAltura = altura - 2 * MARGEM - ALTURA_DO_ROTULO;
        float escala = Math.min(AMPLIACAO_MAXIMA, Math.min(areaLargura / imagem.getWidth(), areaAltura / imagem.getHeight()));
        float w = imagem.getWidth() * escala;
        float h = imagem.getHeight() * escala;
        try (PDPageContentStream conteudo = new PDPageContentStream(pdf, pagina)) {
            rotulo(conteudo, fonte, item.rotulo(), altura);
            conteudo.drawImage(imagem, MARGEM + (areaLargura - w) / 2, altura - MARGEM - ALTURA_DO_ROTULO - h, w, h);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean documento(PDDocument pdf, PDType1Font fonte, Item item, List<PDDocument> abertos) {
        PDDocument outro;
        try {
            outro = Loader.loadPDF(item.arquivo().toFile());
        } catch (IOException e) {
            return false; // com senha ou corrompido
        }
        abertos.add(outro); // as páginas importadas precisam do original aberto até gravar
        try {
            PDPage capa = new PDPage(PDRectangle.A4);
            pdf.addPage(capa);
            try (PDPageContentStream conteudo = new PDPageContentStream(pdf, capa)) {
                rotulo(conteudo, fonte, item.rotulo() + " (" + outro.getNumberOfPages()
                        + (outro.getNumberOfPages() == 1 ? " página a seguir)" : " páginas a seguir)"), capa.getMediaBox().getHeight());
            }
            for (PDPage pagina : outro.getPages()) {
                pdf.importPage(pagina);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void rotulo(PDPageContentStream conteudo, PDType1Font fonte, String texto, float altura) throws IOException {
        conteudo.beginText();
        conteudo.setFont(fonte, 11);
        conteudo.newLineAtOffset(MARGEM, altura - MARGEM - 12);
        conteudo.showText(seguro(texto, fonte));
        conteudo.endText();
    }

    /** O rótulo com os caracteres que a fonte padrão do PDF escreve (o resto vira "?"), e curto. */
    static String seguro(String texto, PDType1Font fonte) {
        String curto = texto == null ? "" : texto.strip();
        if (curto.length() > MAXIMO_DO_ROTULO) {
            curto = curto.substring(0, MAXIMO_DO_ROTULO - 1).strip() + "...";
        }
        StringBuilder seguro = new StringBuilder();
        curto.codePoints().forEach(c -> {
            String letra = Character.toString(c);
            try {
                fonte.encode(letra);
                seguro.append(letra);
            } catch (IOException | IllegalArgumentException e) {
                seguro.append('?');
            }
        });
        return seguro.toString();
    }

    private static void mover(Path origem, Path destino) throws IOException {
        try {
            Files.move(origem, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
