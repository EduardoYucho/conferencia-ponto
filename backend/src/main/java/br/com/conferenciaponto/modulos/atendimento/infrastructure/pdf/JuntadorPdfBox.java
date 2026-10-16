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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * O PDF do pacote pelo PDFBox: o texto da conversa nas primeiras páginas (cada item com o identificador em negrito),
 * depois cada imagem numa página A4 com o rótulo no alto e cada PDF depois de uma página com o rótulo. O que não abre
 * (formato que o PDFBox não lê, PDF com senha) fica de fora; o resto entra. A fonte é a Helvetica do próprio PDF: o
 * português sai inteiro; emoji e outros símbolos que ela não tem saem do texto.
 */
@Component
public class JuntadorPdfBox implements JuntadorDePdf {

    private static final Logger log = LoggerFactory.getLogger(JuntadorPdfBox.class);
    private static final float MARGEM = 36;
    private static final float ALTURA_DO_ROTULO = 28;
    private static final int MAXIMO_DO_ROTULO = 110;
    /** Imagem pequena pode crescer um pouco para ficar legível, não mais que isso. */
    private static final float AMPLIACAO_MAXIMA = 1.5f;
    private static final float CORPO = 9.5f;
    private static final float ENTRELINHA = 12.5f;
    private static final Pattern CABECALHO_DE_ITEM = Pattern.compile("^\\[[A-Z]{1,2}\\d{1,5}].*");
    /** Quantas vezes o texto é refeito até o número de páginas dele parar de mudar (os números das páginas mudam). */
    private static final int AJUSTES = 5;

    private record Linha(String texto, boolean negrito) {
    }

    @Override
    public Resultado juntar(Function<List<Integer>, String> texto, List<Item> itens, Path destino) {
        List<PDDocument> abertos = new ArrayList<>();
        try (PDDocument pdf = new PDDocument()) {
            PDType1Font normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font negrito = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            // cada item aberto antes, para saber quantas páginas ele ocupa
            List<Object> fontes = new ArrayList<>();
            List<Integer> tamanhos = new ArrayList<>();
            for (Item item : itens) {
                Object aberto = abrir(pdf, item, abertos);
                fontes.add(aberto);
                tamanhos.add(aberto instanceof PDDocument outro ? 1 + outro.getNumberOfPages() : aberto != null ? 1 : 0);
            }

            List<List<Linha>> paginasDoTexto = List.of();
            if (texto != null) {
                Escrita escrita = new Escrita(normal, negrito);
                int paginas = 1;
                for (int i = 0; i < AJUSTES; i++) {
                    paginasDoTexto = escrita.paginar(texto.apply(inicios(tamanhos, paginas)));
                    int agora = Math.max(1, paginasDoTexto.size());
                    if (agora == paginas) {
                        break;
                    }
                    paginas = agora;
                }
                for (List<Linha> pagina : paginasDoTexto) {
                    escrever(pdf, pagina, normal, negrito);
                }
            }

            List<Integer> paginas = new ArrayList<>();
            for (int i = 0; i < itens.size(); i++) {
                int primeira = pdf.getNumberOfPages() + 1;
                Object aberto = fontes.get(i);
                boolean entrou = aberto instanceof PDDocument outro ? documento(pdf, negrito, itens.get(i), outro)
                        : aberto instanceof PDImageXObject imagem && imagem(pdf, negrito, itens.get(i), imagem);
                paginas.add(entrou ? primeira : 0);
            }
            int total = pdf.getNumberOfPages();
            if (total > 0) {
                gravar(pdf, destino);
            }
            return new Resultado(List.copyOf(paginas), total, paginasDoTexto.size());
        } catch (IOException e) {
            log.warn("Não foi possível gravar o PDF do pacote para o Gemini: {}", e.getClass().getSimpleName());
            return new Resultado(itens.stream().map(i -> 0).toList(), 0, 0);
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

    /** A primeira página de cada item, com o texto ocupando as primeiras {@code paginasDoTexto} (0 = não entra). */
    private static List<Integer> inicios(List<Integer> tamanhos, int paginasDoTexto) {
        List<Integer> inicios = new ArrayList<>();
        int pagina = paginasDoTexto + 1;
        for (int tamanho : tamanhos) {
            inicios.add(tamanho == 0 ? 0 : pagina);
            pagina += tamanho;
        }
        return inicios;
    }

    /** A imagem (já dentro do PDF novo) ou o PDF de origem (aberto até gravar), ou null se não abre. */
    private static Object abrir(PDDocument pdf, Item item, List<PDDocument> abertos) {
        if (item.pdf()) {
            try {
                PDDocument outro = Loader.loadPDF(item.arquivo().toFile());
                abertos.add(outro); // as páginas importadas precisam do original aberto até gravar
                return outro;
            } catch (IOException e) {
                return null; // com senha ou corrompido
            }
        }
        try {
            return PDImageXObject.createFromFileByContent(item.arquivo().toFile(), pdf);
        } catch (IOException | IllegalArgumentException e) {
            return null; // formato que o PDFBox não lê (ex.: WebP, HEIC)
        }
    }

    private static boolean imagem(PDDocument pdf, PDType1Font fonte, Item item, PDImageXObject imagem) {
        PDPage pagina = new PDPage(PDRectangle.A4);
        float largura = pagina.getMediaBox().getWidth();
        float altura = pagina.getMediaBox().getHeight();
        float areaLargura = largura - 2 * MARGEM;
        float areaAltura = altura - 2 * MARGEM - ALTURA_DO_ROTULO;
        float escala = Math.min(AMPLIACAO_MAXIMA, Math.min(areaLargura / imagem.getWidth(), areaAltura / imagem.getHeight()));
        float w = imagem.getWidth() * escala;
        float h = imagem.getHeight() * escala;
        pdf.addPage(pagina);
        try (PDPageContentStream conteudo = new PDPageContentStream(pdf, pagina)) {
            rotulo(conteudo, fonte, item.rotulo(), altura);
            conteudo.drawImage(imagem, MARGEM + (areaLargura - w) / 2, altura - MARGEM - ALTURA_DO_ROTULO - h, w, h);
            return true;
        } catch (IOException e) {
            pdf.removePage(pagina);
            return false;
        }
    }

    private static boolean documento(PDDocument pdf, PDType1Font fonte, Item item, PDDocument outro) {
        int antes = pdf.getNumberOfPages();
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
            while (pdf.getNumberOfPages() > antes) {
                pdf.removePage(pdf.getNumberOfPages() - 1);
            }
            return false;
        }
    }

    private static void escrever(PDDocument pdf, List<Linha> linhas, PDType1Font normal, PDType1Font negrito)
            throws IOException {
        PDPage pagina = new PDPage(PDRectangle.A4);
        pdf.addPage(pagina);
        float y = pagina.getMediaBox().getHeight() - MARGEM - CORPO;
        try (PDPageContentStream conteudo = new PDPageContentStream(pdf, pagina)) {
            for (Linha linha : linhas) {
                if (!linha.texto().isEmpty()) {
                    conteudo.beginText();
                    conteudo.setFont(linha.negrito() ? negrito : normal, CORPO);
                    conteudo.newLineAtOffset(MARGEM, y);
                    conteudo.showText(linha.texto());
                    conteudo.endText();
                }
                y -= ENTRELINHA;
            }
        }
    }

    /** Quebra o texto em linhas que cabem na largura da página e as linhas em páginas. */
    private static final class Escrita {

        private final PDType1Font normal;
        private final PDType1Font negrito;
        private final Map<Integer, Boolean> escreviveis = new HashMap<>();
        private final float largura = PDRectangle.A4.getWidth() - 2 * MARGEM;
        private final int linhasPorPagina = (int) ((PDRectangle.A4.getHeight() - 2 * MARGEM) / ENTRELINHA);

        Escrita(PDType1Font normal, PDType1Font negrito) {
            this.normal = normal;
            this.negrito = negrito;
        }

        List<List<Linha>> paginar(String texto) {
            List<Linha> linhas = new ArrayList<>();
            boolean primeira = true;
            for (String original : (texto == null ? "" : texto).replace("\r", "").split("\n", -1)) {
                String limpa = escrevivel(original.replace("\t", "    ").stripTrailing());
                boolean emNegrito = primeira || CABECALHO_DE_ITEM.matcher(limpa).matches();
                primeira = false;
                quebrar(limpa, emNegrito ? negrito : normal, emNegrito, linhas);
            }
            List<List<Linha>> paginas = new ArrayList<>();
            for (int i = 0; i < linhas.size(); i += linhasPorPagina) {
                paginas.add(linhas.subList(i, Math.min(linhas.size(), i + linhasPorPagina)));
            }
            return paginas;
        }

        private void quebrar(String texto, PDType1Font fonte, boolean emNegrito, List<Linha> linhas) {
            if (texto.isEmpty()) {
                linhas.add(new Linha("", false));
                return;
            }
            StringBuilder atual = new StringBuilder();
            for (String palavra : texto.split(" ")) {
                String tentativa = atual.isEmpty() ? palavra : atual + " " + palavra;
                if (largura(tentativa, fonte) <= largura) {
                    atual.setLength(0);
                    atual.append(tentativa);
                    continue;
                }
                if (!atual.isEmpty()) {
                    linhas.add(new Linha(atual.toString(), emNegrito));
                    atual.setLength(0);
                }
                // palavra maior que a linha (um link, um código): corta onde couber
                String resto = palavra;
                while (largura(resto, fonte) > largura) {
                    int corte = resto.length() - 1;
                    while (corte > 1 && largura(resto.substring(0, corte), fonte) > largura) {
                        corte--;
                    }
                    linhas.add(new Linha(resto.substring(0, corte), emNegrito));
                    resto = resto.substring(corte);
                }
                atual.append(resto);
            }
            linhas.add(new Linha(atual.toString(), emNegrito));
        }

        private float largura(String texto, PDType1Font fonte) {
            try {
                return fonte.getStringWidth(texto) / 1000 * CORPO;
            } catch (IOException | IllegalArgumentException e) {
                return Float.MAX_VALUE;
            }
        }

        /** Só os caracteres que a Helvetica do PDF escreve: símbolos e emoji saem, o resto que falta vira "?". */
        private String escrevivel(String texto) {
            StringBuilder t = new StringBuilder();
            texto.codePoints().forEach(c -> {
                if (Boolean.TRUE.equals(escreviveis.computeIfAbsent(c, this::escreve))) {
                    t.appendCodePoint(c);
                } else if (!descartavel(c)) {
                    t.append('?');
                }
            });
            return t.toString();
        }

        private boolean escreve(int c) {
            String letra = Character.toString(c);
            try {
                normal.encode(letra);
                negrito.encode(letra);
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        }

        private static boolean descartavel(int c) {
            int tipo = Character.getType(c);
            return c > 0xFFFF || tipo == Character.OTHER_SYMBOL || tipo == Character.NON_SPACING_MARK
                    || tipo == Character.FORMAT || tipo == Character.CONTROL || tipo == Character.SURROGATE
                    || tipo == Character.ENCLOSING_MARK;
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

    private static void gravar(PDDocument pdf, Path destino) throws IOException {
        Files.createDirectories(destino.getParent());
        Path temporario = Files.createTempFile(destino.getParent(), ".pdf-", ".tmp");
        try {
            pdf.save(temporario.toFile());
            try {
                Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporario);
        }
    }
}
