package br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ExtratorDePdf;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeituraDoPdfException;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LinkDoPdf;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.PdfPosicionado;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Trecho;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lê o PDF com o PDFBox: título, texto letra a letra com a posição (agrupado em trechos) e links. Não interpreta
 * nada do Digisac (isso é do {@code LeitorConversaDigisac}).
 *
 * <p>Os trechos são letras seguidas, na mesma linha e do mesmo tamanho; um espaço grande ou uma troca de tamanho
 * começa outro trecho (é assim que o horário, menor, se separa do remetente).
 */
@Component
public class ExtratorPdfBox implements ExtratorDePdf {

    /** Diferença de tamanho de letra que separa trechos. */
    private static final float OUTRO_TAMANHO = 0.6f;
    /** Letras a até esta distância vertical da linha estão nela. */
    private static final float MESMA_LINHA = 2f;

    /** Uma letra do PDF. */
    record Letra(float x, float largura, float y, float tamanho, String texto) {
    }

    @Override
    public PdfPosicionado extrair(Path arquivo) {
        conferirAssinatura(arquivo);
        try (PDDocument documento = Loader.loadPDF(arquivo.toFile())) {
            PDDocumentInformation informacoes = documento.getDocumentInformation();
            String titulo = informacoes == null ? null : informacoes.getTitle();
            Coletor coletor = new Coletor(documento.getNumberOfPages());
            coletor.getText(documento);
            List<Trecho> trechos = new ArrayList<>();
            List<LinkDoPdf> links = new ArrayList<>();
            float largura = 0;
            for (int i = 0; i < documento.getNumberOfPages(); i++) {
                PDPage pagina = documento.getPage(i);
                PDRectangle area = pagina.getCropBox();
                largura = Math.max(largura, area.getWidth());
                trechos.addAll(trechos(i + 1, coletor.letras.get(i)));
                links.addAll(links(i + 1, pagina, area));
            }
            return new PdfPosicionado(titulo, documento.getNumberOfPages(), largura, trechos, links);
        } catch (InvalidPasswordException e) {
            throw LeituraDoPdfException.protegido();
        } catch (IOException | RuntimeException e) {
            // arquivo truncado, estrutura quebrada ou conteúdo que o PDFBox não entende
            throw LeituraDoPdfException.invalido();
        }
    }

    private static void conferirAssinatura(Path arquivo) {
        byte[] inicio = new byte[5];
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            if (entrada.readNBytes(inicio, 0, 5) < 5 || !new String(inicio, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw LeituraDoPdfException.invalido();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o PDF recebido", e);
        }
    }

    /** Guarda as letras de cada página (já com os acentos juntados pelo PDFBox), sem montar texto. */
    private static final class Coletor extends PDFTextStripper {
        final List<List<Letra>> letras = new ArrayList<>();

        Coletor(int paginas) throws IOException {
            setSortByPosition(false);
            for (int i = 0; i < paginas; i++) {
                letras.add(new ArrayList<>());
            }
        }

        @Override
        protected void writePage() {
            List<Letra> daPagina = letras.get(getCurrentPageNo() - 1);
            for (List<TextPosition> artigo : getCharactersByArticle()) {
                for (TextPosition t : artigo) {
                    String texto = t.getUnicode();
                    if (texto != null && !texto.isEmpty()) {
                        daPagina.add(new Letra(t.getXDirAdj(), t.getWidthDirAdj(), t.getYDirAdj(), t.getFontSizeInPt(), texto));
                    }
                }
            }
        }
    }

    /** Agrupa as letras da página em linhas (mesma altura e mesmo tamanho) e as linhas em trechos. */
    static List<Trecho> trechos(int pagina, List<Letra> letras) {
        List<Letra> ordenadas = new ArrayList<>(letras);
        ordenadas.sort(Comparator.comparingDouble(Letra::y).thenComparingDouble(Letra::x));
        List<List<Letra>> linhas = new ArrayList<>();
        for (Letra letra : ordenadas) {
            List<Letra> destino = null;
            for (int i = linhas.size() - 1; i >= 0 && destino == null; i--) {
                Letra modelo = linhas.get(i).get(0);
                if (letra.y() - modelo.y() > MESMA_LINHA) {
                    break;
                }
                if (Math.abs(letra.tamanho() - modelo.tamanho()) < OUTRO_TAMANHO) {
                    destino = linhas.get(i);
                }
            }
            if (destino == null) {
                destino = new ArrayList<>();
                linhas.add(destino);
            }
            destino.add(letra);
        }
        List<Trecho> trechos = new ArrayList<>();
        for (List<Letra> linha : linhas) {
            linha.sort(Comparator.comparingDouble(Letra::x));
            trechos.addAll(dividir(pagina, linha));
        }
        return trechos;
    }

    /** Divide uma linha nos espaços grandes; põe espaço onde o PDF deixou só o vão entre as palavras. */
    private static List<Trecho> dividir(int pagina, List<Letra> linha) {
        float larguraMedia = (float) linha.stream().filter(l -> !l.texto().isBlank())
                .mapToDouble(l -> l.largura() / Math.max(1, l.texto().length())).average().orElse(3);
        float espaco = Math.max(0.5f, larguraMedia * 0.3f);
        float separa = Math.max(6f, larguraMedia * 2.5f);
        List<Trecho> trechos = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        Letra primeira = null;
        Letra anterior = null;
        for (Letra letra : linha) {
            if (anterior != null) {
                float vao = letra.x() - (anterior.x() + anterior.largura());
                if (vao > separa) {
                    trechos.add(trecho(pagina, primeira, anterior, texto));
                    texto.setLength(0);
                    primeira = null;
                } else if (vao > espaco && !anterior.texto().isBlank() && !letra.texto().isBlank()) {
                    texto.append(' ');
                }
            }
            if (primeira == null) {
                primeira = letra;
            }
            texto.append(letra.texto());
            anterior = letra;
        }
        if (primeira != null) {
            trechos.add(trecho(pagina, primeira, anterior, texto));
        }
        trechos.removeIf(t -> t.texto().isBlank());
        return trechos;
    }

    private static Trecho trecho(int pagina, Letra primeira, Letra ultima, CharSequence texto) {
        return new Trecho(pagina, primeira.x(), ultima.x() + ultima.largura(), primeira.y(), primeira.tamanho(),
                texto.toString().replaceAll("\\s+", " ").strip());
    }

    private static List<LinkDoPdf> links(int numero, PDPage pagina, PDRectangle area) throws IOException {
        List<LinkDoPdf> links = new ArrayList<>();
        float topo = area.getUpperRightY();
        float esquerda = area.getLowerLeftX();
        for (PDAnnotation anotacao : pagina.getAnnotations()) {
            if (!(anotacao instanceof PDAnnotationLink link)) {
                continue;
            }
            PDAction acao = link.getAction();
            PDRectangle r = link.getRectangle();
            if (acao instanceof PDActionURI uri && uri.getURI() != null && r != null) {
                links.add(new LinkDoPdf(numero, r.getLowerLeftX() - esquerda, r.getUpperRightX() - esquerda,
                        topo - r.getUpperRightY(), topo - r.getLowerLeftY(), uri.getURI()));
            }
        }
        return links;
    }
}
