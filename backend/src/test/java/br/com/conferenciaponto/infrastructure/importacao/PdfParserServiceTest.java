package br.com.conferenciaponto.infrastructure.importacao;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfParserServiceTest {

    private static final LocalDateTime ESPERADO = LocalDateTime.of(2026, 9, 28, 8, 2, 31);

    private final PdfParserService parser = new PdfParserService(PdfParserService.REGEX_PADRAO, 10 * 1024 * 1024);

    @TempDir
    Path pasta;

    /** Gera um PDF real com as linhas informadas (Helvetica, uma por linha). */
    static Path gerarPdf(Path destino, List<String> linhas) throws IOException {
        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage();
            documento.addPage(pagina);
            try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
                conteudo.beginText();
                conteudo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                conteudo.setLeading(16);
                conteudo.newLineAtOffset(50, 720);
                for (String linha : linhas) {
                    conteudo.showText(linha);
                    conteudo.newLine();
                }
                conteudo.endText();
            }
            documento.save(destino.toFile());
        }
        return destino;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("Regex captura o padrão e variações comuns da extração de texto")
    @ValueSource(strings = {
            "Comprovante de Ponto - 28/09/2026 08:02:31",
            "Empresa X\nComprovante de Ponto - 28/09/2026 08:02:31\nNSR 000123",
            "COMPROVANTE DE PONTO – 28/09/2026 08:02:31",          // en-dash e maiúsculas
            "Comprovante  de\nPonto -\n28/09/2026\n08:02:31",      // quebras de linha da extração
            "Comprovante de Ponto - 28/09/2026 às 08:02:31",
            "Comprovante de Ponto - 28/09/2026 08:02:31"  // espaço não separável
    })
    void capturaDataHora(String texto) {
        assertThat(parser.extrairDataHora(texto)).contains(ESPERADO);
    }

    @Test
    @DisplayName("Hora sem segundos é aceita (segundos = 00)")
    void horaSemSegundos() {
        assertThat(parser.extrairDataHora("Comprovante de Ponto - 28/09/2026 17:50"))
                .contains(LocalDateTime.of(2026, 9, 28, 17, 50, 0));
    }

    @Test
    @DisplayName("Data impossível é descartada e a próxima ocorrência válida é usada")
    void dataInvalida() {
        assertThat(parser.extrairDataHora("Comprovante de Ponto - 31/02/2026 08:00:00")).isEmpty();
        assertThat(parser.extrairDataHora(
                "Comprovante de Ponto - 31/02/2026 08:00:00\nComprovante de Ponto - 28/09/2026 08:02:31"))
                .contains(ESPERADO);
    }

    @Test
    @DisplayName("Texto sem o padrão não gera data/hora")
    void semPadrao() {
        assertThat(parser.extrairDataHora("Boleto bancário - vencimento 28/09/2026")).isEmpty();
        assertThat(parser.extrairDataHora(null)).isEmpty();
    }

    @Test
    @DisplayName("PDF real: PDFBox extrai o texto e o parser encontra a batida")
    void pdfReal() throws IOException {
        Path pdf = gerarPdf(pasta.resolve("comprovante.pdf"), List.of(
                "Registro Eletronico de Ponto",
                "Comprovante de Ponto - 28/09/2026 08:02:31",
                "NSR: 000000123"));

        PdfParserService.ComprovanteLido lido = parser.ler(pdf);

        assertThat(lido.nomeArquivo()).isEqualTo("comprovante.pdf");
        assertThat(lido.dataHora()).contains(ESPERADO);
        assertThat(lido.hashSha256()).hasSize(64);
    }

    /**
     * Imita o comprovante do Ponto Fácil (gerado pelo TCPDF): fonte TrueType embutida em Identity-H com
     * ToUnicode, cabeçalho montado em pedaços posicionados separadamente e rodapé do TCPDF. O PDF real
     * não entra no repositório (a assinatura digital embutida tem dados pessoais).
     */
    static Path gerarComprovanteEstiloTcpdf(Path destino) throws IOException {
        try (PDDocument documento = new PDDocument();
             InputStream ttf = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDDocumentInformation info = documento.getDocumentInformation();
            info.setTitle("Comprovante de Ponto do Trabalhador");
            info.setCreator("TCPDF");
            info.setProducer("TCPDF 6.7.6 (http://www.tcpdf.org)");
            PDType0Font fonte = PDType0Font.load(documento, ttf);
            PDPage pagina = new PDPage(PDRectangle.A4);
            documento.addPage(pagina);
            try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
                escrever(conteudo, fonte, 14, 40, 790, "Comprovante de Ponto");
                escrever(conteudo, fonte, 14, 184, 790, "-");
                escrever(conteudo, fonte, 14, 194, 790, "28/09/2026");
                escrever(conteudo, fonte, 14, 276, 790, "08:02:31");
                escrever(conteudo, fonte, 8, 40, 30, "Powered by TCPDF (www.tcpdf.org)");
            }
            documento.save(destino.toFile());
        }
        return destino;
    }

    private static void escrever(PDPageContentStream conteudo, PDType0Font fonte, float tamanho,
                                 float x, float y, String texto) throws IOException {
        conteudo.beginText();
        conteudo.setFont(fonte, tamanho);
        conteudo.newLineAtOffset(x, y);
        conteudo.showText(texto);
        conteudo.endText();
    }

    @Test
    @DisplayName("Comprovante no formato do Ponto Fácil (TCPDF, fonte embutida): data/hora lida do cabeçalho")
    void comprovanteFormatoPontoFacil() throws Exception {
        Path pdf = gerarComprovanteEstiloTcpdf(pasta.resolve("comprovanteponto - 2026-09-28T080231.262.pdf"));

        PdfParserService.ComprovanteLido lido = parser.ler(pdf);

        assertThat(lido.dataHora()).contains(ESPERADO);
        assertThat(lido.nomeArquivo()).isEqualTo("comprovanteponto - 2026-09-28T080231.262.pdf");
    }

    @Test
    @DisplayName("Hash depende só do conteúdo: mesmo PDF com outro nome tem o mesmo hash")
    void hashPorConteudo() throws IOException {
        Path original = gerarPdf(pasta.resolve("a.pdf"), List.of("Comprovante de Ponto - 28/09/2026 08:02:31"));
        Path copia = Files.copy(original, pasta.resolve("a (1).pdf"));

        assertThat(parser.lerComBloqueio(copia).hashSha256())
                .isEqualTo(parser.lerComBloqueio(original).hashSha256());
    }

    @Test
    @DisplayName("Arquivo vazio (download em andamento) sinaliza para tentar de novo")
    void arquivoVazio() throws IOException {
        Path vazio = Files.createFile(pasta.resolve("baixando.pdf"));
        assertThatThrownBy(() -> parser.lerComBloqueio(vazio)).isInstanceOf(ArquivoEmUsoException.class);
    }

    @Test
    @DisplayName("Arquivo .pdf que não é PDF resulta em data/hora vazia (sem exceção)")
    void naoEhPdf() throws IOException {
        Path falso = Files.writeString(pasta.resolve("falso.pdf"), "isto não é um PDF");
        assertThat(parser.ler(falso).dataHora()).isEmpty();
    }
}
