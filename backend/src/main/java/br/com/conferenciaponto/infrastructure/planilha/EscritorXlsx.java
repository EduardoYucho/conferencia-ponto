package br.com.conferenciaponto.infrastructure.planilha;

import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.Celula;
import br.com.conferenciaponto.application.planilha.Estilo;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Grava a planilha de conferência como arquivo do Excel (.xlsx), sem bibliotecas: um zip com os XML do formato
 * (pasta de trabalho, estilos e uma planilha por aba).
 *
 * <p>Durações são números com o formato {@code [h]:mm:ss} — somam e filtram como horas. A pasta usa o sistema
 * de datas de 1904 porque só nele o Excel mostra durações negativas (o débito do banco de horas); por isso as
 * datas vão como texto: não mudam se a linha for copiada para outra pasta de trabalho.
 */
public final class EscritorXlsx {

    public static final String TIPO_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String CABECALHO_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final int FONTE_PADRAO = 10;

    private static final int FMT_GERAL = 0;
    private static final int FMT_INTEIRO = 1;
    private static final int FMT_DURACAO = 164;
    private static final int FMT_HORA = 165;

    private EscritorXlsx() {
    }

    public static byte[] escrever(PlanilhaConferencia planilha) {
        Estilos estilos = new Estilos();
        List<String> planilhas = new ArrayList<>();
        for (int i = 0; i < planilha.abas().size(); i++) {
            planilhas.add(aba(planilha.abas().get(i), estilos, i == 0));
        }
        ByteArrayOutputStream saida = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(saida, StandardCharsets.UTF_8)) {
            gravar(zip, "[Content_Types].xml", tiposDeConteudo(planilhas.size()));
            gravar(zip, "_rels/.rels", CABECALHO_XML
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            gravar(zip, "xl/workbook.xml", pasta(planilha));
            gravar(zip, "xl/_rels/workbook.xml.rels", relacoes(planilhas.size()));
            gravar(zip, "xl/styles.xml", estilos.xml());
            for (int i = 0; i < planilhas.size(); i++) {
                gravar(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", planilhas.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saida.toByteArray();
    }

    private static void gravar(ZipOutputStream zip, String nome, String conteudo) throws IOException {
        zip.putNextEntry(new ZipEntry(nome));
        zip.write(conteudo.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String tiposDeConteudo(int abas) {
        StringBuilder xml = new StringBuilder(CABECALHO_XML)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= abas; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private static String pasta(PlanilhaConferencia planilha) {
        StringBuilder xml = new StringBuilder(CABECALHO_XML)
                .append("<workbook xmlns=\"").append(NS).append("\" xmlns:r=\"").append(NS_REL).append("\">")
                .append("<workbookPr date1904=\"1\"/>")
                .append("<bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews>")
                .append("<sheets>");
        for (int i = 0; i < planilha.abas().size(); i++) {
            xml.append("<sheet name=\"").append(xml(nomeDeAba(planilha.abas().get(i).titulo())))
                    .append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        // sem valores em cache confiáveis para quem editar: o Excel recalcula tudo ao abrir
        return xml.append("</sheets><calcPr calcId=\"0\" fullCalcOnLoad=\"1\"/></workbook>").toString();
    }

    private static String relacoes(int abas) {
        StringBuilder xml = new StringBuilder(CABECALHO_XML)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= abas; i++) {
            xml.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(NS_REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        return xml.append("<Relationship Id=\"rId").append(abas + 1).append("\" Type=\"").append(NS_REL)
                .append("/styles\" Target=\"styles.xml\"/></Relationships>").toString();
    }

    private static String aba(Aba aba, Estilos estilos, boolean selecionada) {
        int colunas = aba.colunas();
        int linhas = Math.max(aba.linhas().size(), 1);
        StringBuilder xml = new StringBuilder(CABECALHO_XML)
                .append("<worksheet xmlns=\"").append(NS).append("\">")
                .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                .append("<dimension ref=\"A1:").append(Aba.ref(linhas - 1, colunas - 1)).append("\"/>")
                .append("<sheetViews><sheetView workbookViewId=\"0\"").append(selecionada ? " tabSelected=\"1\"" : "").append(">");
        if (aba.linhasCongeladas() > 0) {
            String primeira = Aba.ref(aba.linhasCongeladas(), 0);
            xml.append("<pane ySplit=\"").append(aba.linhasCongeladas()).append("\" topLeftCell=\"").append(primeira)
                    .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                    .append("<selection pane=\"bottomLeft\" activeCell=\"").append(primeira).append("\" sqref=\"")
                    .append(primeira).append("\"/>");
        }
        xml.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int c = 0; c < colunas; c++) {
            // largura em "caracteres" do Excel: (pixels - 5) / 7
            xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(numero((aba.largurasPx().get(c) - 5) / 7.0)).append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>");
        for (int l = 0; l < aba.linhas().size(); l++) {
            StringBuilder celulas = new StringBuilder();
            List<Celula> linha = aba.linhas().get(l);
            for (int c = 0; c < linha.size(); c++) {
                celula(celulas, linha.get(c), Aba.ref(l, c), estilos);
            }
            if (!celulas.isEmpty()) {
                xml.append("<row r=\"").append(l + 1).append("\">").append(celulas).append("</row>");
            }
        }
        return xml.append("</sheetData>")
                .append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"1\"/>")
                .append("</worksheet>").toString();
    }

    private static void celula(StringBuilder xml, Celula celula, String ref, Estilos estilos) {
        int estilo = estilos.indice(celula.estilo(), formato(celula));
        switch (celula.tipo()) {
            case VAZIA -> {
                if (estilo != 0) {
                    xml.append("<c r=\"").append(ref).append("\" s=\"").append(estilo).append("\"/>");
                }
            }
            case TEXTO -> texto(xml, ref, estilo, celula.texto());
            case DATA -> texto(xml, ref, estilo, DATA.format(celula.data()));
            case HORA, DURACAO, INTEIRO -> {
                xml.append("<c r=\"").append(ref).append("\" s=\"").append(estilo).append("\">");
                if (celula.temFormula()) {
                    xml.append("<f>").append(xml(celula.formula())).append("</f>");
                }
                xml.append("<v>").append(celula.tipo() == Celula.Tipo.INTEIRO ? Long.toString(celula.valor())
                        : numero(celula.fracaoDoDia())).append("</v></c>");
            }
        }
    }

    private static void texto(StringBuilder xml, String ref, int estilo, String texto) {
        xml.append("<c r=\"").append(ref).append("\" s=\"").append(estilo)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(texto)).append("</t></is></c>");
    }

    private static int formato(Celula celula) {
        return switch (celula.tipo()) {
            case HORA -> FMT_HORA;
            case DURACAO -> FMT_DURACAO;
            case INTEIRO -> FMT_INTEIRO;
            default -> FMT_GERAL;
        };
    }

    private static String numero(double valor) {
        return Double.toString(valor);
    }

    /** O Excel aceita até 31 caracteres e não aceita / \ ? * [ ] : no nome da aba. */
    static String nomeDeAba(String titulo) {
        String limpo = titulo.replaceAll("[/\\\\?*\\[\\]:]", " ").strip();
        return limpo.length() > 31 ? limpo.substring(0, 31) : limpo;
    }

    /** Escapa o texto para XML e descarta caracteres de controle que o formato não aceita. */
    static String xml(String texto) {
        StringBuilder saida = new StringBuilder(texto.length() + 16);
        texto.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> saida.append("&amp;");
                case '<' -> saida.append("&lt;");
                case '>' -> saida.append("&gt;");
                case '"' -> saida.append("&quot;");
                default -> {
                    boolean valido = c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                            || (c >= 0xE000 && c <= 0xFFFD) || c >= 0x10000;
                    if (valido) {
                        saida.appendCodePoint(c);
                    }
                }
            }
        });
        return saida.toString();
    }

    /** Tabelas de fontes, preenchimentos e estilos de célula, montadas conforme as células aparecem. */
    private static final class Estilos {

        private record Fonte(boolean negrito, boolean italico, int tamanho, Estilo.Cor cor) {
        }

        private record Xf(int formato, int fonte, int fundo, int borda, Estilo.Alinhamento alinhamento, boolean quebra) {
        }

        private final Map<Fonte, Integer> fontes = new LinkedHashMap<>();
        private final Map<Estilo.Cor, Integer> fundos = new LinkedHashMap<>();
        private final Map<Xf, Integer> xfs = new LinkedHashMap<>();

        Estilos() {
            indice(Estilo.NORMAL, FMT_GERAL); // o estilo 0 é o padrão da planilha
        }

        int indice(Estilo e, int formato) {
            Fonte fonte = new Fonte(e.negrito(), e.italico(), e.tamanho() == 0 ? FONTE_PADRAO : e.tamanho(),
                    e.texto() == null ? Estilo.Cor.TINTA : e.texto());
            int idFonte = fontes.computeIfAbsent(fonte, f -> fontes.size());
            // 0 = sem preenchimento; 1 = reservado pelo formato (gray125)
            int idFundo = e.fundo() == null ? 0 : fundos.computeIfAbsent(e.fundo(), f -> fundos.size() + 2);
            Xf xf = new Xf(formato, idFonte, idFundo, e.bordaInferior() ? 1 : 0, e.alinhamento(), e.quebra());
            return xfs.computeIfAbsent(xf, x -> xfs.size());
        }

        String xml() {
            StringBuilder xml = new StringBuilder(CABECALHO_XML)
                    .append("<styleSheet xmlns=\"").append(NS).append("\">")
                    .append("<numFmts count=\"2\"><numFmt numFmtId=\"").append(FMT_DURACAO)
                    .append("\" formatCode=\"[h]:mm:ss\"/><numFmt numFmtId=\"").append(FMT_HORA)
                    .append("\" formatCode=\"hh:mm:ss\"/></numFmts>")
                    .append("<fonts count=\"").append(fontes.size()).append("\">");
            for (Fonte f : fontes.keySet()) {
                xml.append("<font>").append(f.negrito() ? "<b/>" : "").append(f.italico() ? "<i/>" : "")
                        .append("<sz val=\"").append(f.tamanho()).append("\"/><color rgb=\"FF").append(f.cor().hex())
                        .append("\"/><name val=\"Calibri\"/><family val=\"2\"/></font>");
            }
            xml.append("</fonts><fills count=\"").append(fundos.size() + 2).append("\">")
                    .append("<fill><patternFill patternType=\"none\"/></fill>")
                    .append("<fill><patternFill patternType=\"gray125\"/></fill>");
            for (Estilo.Cor cor : fundos.keySet()) {
                xml.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF").append(cor.hex())
                        .append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
            }
            xml.append("</fills><borders count=\"2\">")
                    .append("<border><left/><right/><top/><bottom/><diagonal/></border>")
                    .append("<border><left/><right/><top/><bottom style=\"thin\"><color rgb=\"FF")
                    .append(Estilo.Cor.SUAVE.hex()).append("\"/></bottom><diagonal/></border></borders>")
                    .append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
                    .append("<cellXfs count=\"").append(xfs.size()).append("\">");
            for (Xf xf : xfs.keySet()) {
                xml.append("<xf numFmtId=\"").append(xf.formato()).append("\" fontId=\"").append(xf.fonte())
                        .append("\" fillId=\"").append(xf.fundo()).append("\" borderId=\"").append(xf.borda())
                        .append("\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">")
                        .append("<alignment");
                switch (xf.alinhamento()) {
                    case ESQUERDA -> xml.append(" horizontal=\"left\"");
                    case CENTRO -> xml.append(" horizontal=\"center\"");
                    case DIREITA -> xml.append(" horizontal=\"right\"");
                    case PADRAO -> { }
                }
                xml.append(" vertical=\"top\"").append(xf.quebra() ? " wrapText=\"1\"" : "").append("/></xf>");
            }
            return xml.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
                    .append("</styleSheet>").toString();
        }
    }
}
