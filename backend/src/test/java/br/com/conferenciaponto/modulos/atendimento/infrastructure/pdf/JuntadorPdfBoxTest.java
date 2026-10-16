package br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf;

import br.com.conferenciaponto.modulos.atendimento.domain.pacote.JuntadorDePdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** As imagens e os PDFs do atendimento num PDF só, com o identificador no alto de cada item. */
class JuntadorPdfBoxTest {

    @TempDir
    Path pasta;

    private Path imagem(String nome, String formato, int largura, int altura) throws IOException {
        Path arquivo = pasta.resolve(nome);
        ImageIO.write(new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB), formato, arquivo.toFile());
        return arquivo;
    }

    private Path pdf(String nome, int paginas) throws IOException {
        Path arquivo = pasta.resolve(nome);
        try (PDDocument documento = new PDDocument()) {
            for (int i = 0; i < paginas; i++) {
                documento.addPage(new PDPage());
            }
            documento.save(arquivo.toFile());
        }
        return arquivo;
    }

    @Test
    void cadaItemComORotuloEOQueNaoAbreFicaDeFora() throws IOException {
        Path webp = pasta.resolve("tela.webp");
        Files.write(webp, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
        Path destino = pasta.resolve("saida/Anexos.pdf");

        JuntadorDePdf.Resultado resultado = new JuntadorPdfBox().juntar(List.of(
                new JuntadorDePdf.Item("A1 · imagem do cliente · print.png", imagem("print.png", "png", 1600, 900), false),
                new JuntadorDePdf.Item("A2 · imagem · tela.webp", webp, false),
                new JuntadorDePdf.Item("A3 · documento do cliente · SINTEGRA.pdf", pdf("SINTEGRA.pdf", 2), true),
                new JuntadorDePdf.Item("A4 · imagem · foto ☺.jpeg", imagem("foto.jpeg", "jpeg", 300, 2000), false)), destino);

        assertThat(resultado.paginas()).containsExactly(1, 0, 2, 5);
        assertThat(resultado.total()).isEqualTo(5);
        try (PDDocument lido = Loader.loadPDF(destino.toFile())) {
            assertThat(lido.getNumberOfPages()).isEqualTo(5);
            String texto = new PDFTextStripper().getText(lido);
            assertThat(texto).contains("A1 · imagem do cliente · print.png")
                    .contains("A3 · documento do cliente · SINTEGRA.pdf (2 páginas a seguir)")
                    .contains("A4 · imagem · foto ?.jpeg").doesNotContain("tela.webp");
        }
    }

    @Test
    void semNadaQueAbraNaoGravaArquivo() throws IOException {
        Path quebrado = pasta.resolve("quebrado.pdf");
        Files.writeString(quebrado, "%PDF-1.7 quebrado");
        Path destino = pasta.resolve("Anexos.pdf");

        JuntadorDePdf.Resultado resultado = new JuntadorPdfBox().juntar(
                List.of(new JuntadorDePdf.Item("A1 · documento", quebrado, true)), destino);

        assertThat(resultado.paginas()).containsExactly(0);
        assertThat(resultado.total()).isZero();
        assertThat(destino).doesNotExist();
    }

    @Test
    void aConversaVemAntesComAsPaginasCertasDosItens() throws IOException {
        Path destino = pasta.resolve("Atendimento.pdf");
        StringBuilder conversa = new StringBuilder("PACOTE DO ATENDIMENTO\n");
        for (int i = 1; i <= 90; i++) {
            conversa.append("[M").append(i).append("] 09/10 10:").append(i % 60).append(" · mensagem do cliente:\n")
                    .append("Mensagem número ").append(i).append(" com um emoji \uD83D\uDE00 e acentuação: ação, côco.\n");
        }
        conversa.append("Uma palavra comprida: ").append("x".repeat(400));
        List<List<Integer>> pedidas = new java.util.ArrayList<>();

        JuntadorDePdf.Resultado resultado = new JuntadorPdfBox().juntar(paginas -> {
            pedidas.add(paginas);
            return conversa + "\n[A1] imagem (o conteúdo está neste PDF, na página " + paginas.get(0) + ")\n"
                    + "[A2] documento (a partir da página " + paginas.get(1) + ")";
        }, List.of(new JuntadorDePdf.Item("A1 · imagem", imagem("a.png", "png", 400, 300), false),
                new JuntadorDePdf.Item("A2 · documento", pdf("b.pdf", 2), true)), destino);

        int texto = resultado.paginasDoTexto();
        assertThat(texto).as("90 mensagens não cabem numa página").isGreaterThan(2);
        assertThat(resultado.paginas()).containsExactly(texto + 1, texto + 2);
        assertThat(resultado.total()).isEqualTo(texto + 4);
        assertThat(pedidas.get(pedidas.size() - 1)).containsExactly(texto + 1, texto + 2);
        try (PDDocument lido = Loader.loadPDF(destino.toFile())) {
            PDFTextStripper leitor = new PDFTextStripper();
            String tudo = leitor.getText(lido);
            assertThat(tudo).contains("Mensagem número 90 com um emoji").contains("e acentuação: ação, côco.")
                    .contains("na página " + (texto + 1) + ")").contains("a partir da página " + (texto + 2) + ")")
                    .doesNotContain("?");
            leitor.setStartPage(texto + 1);
            leitor.setEndPage(texto + 1);
            assertThat(leitor.getText(lido)).contains("A1 · imagem");
        }
    }
}
