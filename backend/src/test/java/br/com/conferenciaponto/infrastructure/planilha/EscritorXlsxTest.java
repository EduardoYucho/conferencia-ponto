package br.com.conferenciaponto.infrastructure.planilha;

import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.Celula;
import br.com.conferenciaponto.application.planilha.Estilo;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EscritorXlsxTest {

    private static PlanilhaConferencia planilha() {
        Aba resumo = new Aba(Aba.ID_RESUMO, "Resumo", List.of(
                List.of(Celula.texto("Conferência <de> \"ponto\" & cia", Estilo.TITULO), Celula.VAZIA),
                List.of(Celula.texto("Saldo"), Celula.formulaDuracao("'Jun 2026'!C2", -5_400, Estilo.ROTULO))),
                List.of(190, 118), 0);
        Aba junho = new Aba(202606, "Jun 2026", List.of(
                List.of(Celula.texto("Data", Estilo.CABECALHO), Celula.texto("Entrada 1", Estilo.CABECALHO),
                        Celula.texto("Saldo do dia", Estilo.CABECALHO), Celula.texto("PDFs", Estilo.CABECALHO)),
                List.of(Celula.texto("Total do mês", Estilo.TOTAL), Celula.vazia(Estilo.TOTAL),
                        Celula.formulaDuracao("SUM(C3:C4)", -5_400, Estilo.TOTAL), Celula.formulaInteiro("SUM(D3:D4)", 6, Estilo.TOTAL)),
                List.of(Celula.data(LocalDate.of(2026, 6, 1), Estilo.CENTRO), Celula.hora(LocalTime.of(8, 5, 23), Estilo.CENTRO),
                        Celula.duracao(-7_200, Estilo.NORMAL), Celula.inteiro(4, Estilo.CENTRO)),
                List.of(Celula.data(LocalDate.of(2026, 6, 2), Estilo.CENTRO), Celula.VAZIA,
                        Celula.duracao(1_800, Estilo.NORMAL), Celula.inteiro(2, Estilo.CENTRO))),
                List.of(88, 84, 92, 50), 2);
        return new PlanilhaConferencia("Eduardo", "eduardo", List.of(resumo, junho), Instant.EPOCH);
    }

    private static Map<String, String> partes(byte[] xlsx) throws Exception {
        Map<String, String> partes = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                partes.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return partes;
    }

    private static Document xml(String conteudo) throws Exception {
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setNamespaceAware(true);
        return fabrica.newDocumentBuilder().parse(new ByteArrayInputStream(conteudo.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element celula(Document planilha, String ref) {
        NodeList celulas = planilha.getElementsByTagNameNS("*", "c");
        for (int i = 0; i < celulas.getLength(); i++) {
            Element c = (Element) celulas.item(i);
            if (ref.equals(c.getAttribute("r"))) {
                return c;
            }
        }
        throw new AssertionError("Célula " + ref + " não encontrada");
    }

    @Test
    @DisplayName("O arquivo é um .xlsx completo: todas as partes do pacote, com XML bem formado")
    void pacote() throws Exception {
        Map<String, String> partes = partes(EscritorXlsx.escrever(planilha()));

        assertThat(partes.keySet()).containsExactly("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
        for (String conteudo : partes.values()) {
            xml(conteudo); // lança se não for XML bem formado
        }
        Document pasta = xml(partes.get("xl/workbook.xml"));
        NodeList abas = pasta.getElementsByTagNameNS("*", "sheet");
        assertThat(abas.getLength()).isEqualTo(2);
        assertThat(((Element) abas.item(0)).getAttribute("name")).isEqualTo("Resumo");
        assertThat(((Element) abas.item(1)).getAttribute("name")).isEqualTo("Jun 2026");
        // datas de 1904: só assim o Excel mostra duração negativa
        assertThat(((Element) pasta.getElementsByTagNameNS("*", "workbookPr").item(0)).getAttribute("date1904")).isEqualTo("1");
        assertThat(((Element) pasta.getElementsByTagNameNS("*", "calcPr").item(0)).getAttribute("fullCalcOnLoad")).isEqualTo("1");
    }

    @Test
    @DisplayName("Durações e horários são números (somáveis), datas são texto e fórmulas levam o valor já calculado")
    void celulas() throws Exception {
        Map<String, String> partes = partes(EscritorXlsx.escrever(planilha()));
        Document junho = xml(partes.get("xl/worksheets/sheet2.xml"));

        Element data = celula(junho, "A3");
        assertThat(data.getAttribute("t")).isEqualTo("inlineStr");
        assertThat(data.getTextContent()).isEqualTo("01/06/2026");

        Element entrada = celula(junho, "B3");
        assertThat(entrada.getAttribute("t")).isEmpty();
        assertThat(Double.parseDouble(entrada.getTextContent())).isEqualTo(LocalTime.of(8, 5, 23).toSecondOfDay() / 86_400.0);

        assertThat(Double.parseDouble(celula(junho, "C3").getTextContent())).isEqualTo(-7_200 / 86_400.0);
        assertThat(celula(junho, "D3").getTextContent()).isEqualTo("4");

        Element total = celula(junho, "C2");
        assertThat(total.getElementsByTagNameNS("*", "f").item(0).getTextContent()).isEqualTo("SUM(C3:C4)");
        assertThat(Double.parseDouble(total.getElementsByTagNameNS("*", "v").item(0).getTextContent()))
                .isEqualTo(-5_400 / 86_400.0);

        // cabeçalho e totais fixos ao rolar
        Element painel = (Element) junho.getElementsByTagNameNS("*", "pane").item(0);
        assertThat(painel.getAttribute("ySplit")).isEqualTo("2");
        assertThat(painel.getAttribute("topLeftCell")).isEqualTo("A3");
        assertThat(painel.getAttribute("state")).isEqualTo("frozen");
        assertThat(junho.getElementsByTagNameNS("*", "col").getLength()).isEqualTo(4);

        // texto com caracteres especiais e fórmula apontando para outra aba
        Document resumo = xml(partes.get("xl/worksheets/sheet1.xml"));
        assertThat(celula(resumo, "A1").getTextContent()).isEqualTo("Conferência <de> \"ponto\" & cia");
        assertThat(celula(resumo, "B2").getElementsByTagNameNS("*", "f").item(0).getTextContent()).isEqualTo("'Jun 2026'!C2");
        assertThat(resumo.getElementsByTagNameNS("*", "pane").getLength()).isZero();
    }

    @Test
    @DisplayName("Estilos: formato [h]:mm:ss nas durações e cada célula aponta para um estilo que existe")
    void estilos() throws Exception {
        Map<String, String> partes = partes(EscritorXlsx.escrever(planilha()));
        Document estilos = xml(partes.get("xl/styles.xml"));

        NodeList formatos = estilos.getElementsByTagNameNS("*", "numFmt");
        assertThat(((Element) formatos.item(0)).getAttribute("formatCode")).isEqualTo("[h]:mm:ss");
        assertThat(((Element) formatos.item(1)).getAttribute("formatCode")).isEqualTo("hh:mm:ss");

        Element xfs = (Element) estilos.getElementsByTagNameNS("*", "cellXfs").item(0);
        NodeList lista = xfs.getElementsByTagNameNS("*", "xf");
        assertThat(lista.getLength()).isEqualTo(Integer.parseInt(xfs.getAttribute("count")));
        int fontes = estilos.getElementsByTagNameNS("*", "font").getLength();
        int fundos = estilos.getElementsByTagNameNS("*", "fill").getLength();
        for (int i = 0; i < lista.getLength(); i++) {
            Element xf = (Element) lista.item(i);
            assertThat(Integer.parseInt(xf.getAttribute("fontId"))).isLessThan(fontes);
            assertThat(Integer.parseInt(xf.getAttribute("fillId"))).isLessThan(fundos);
        }

        Document junho = xml(partes.get("xl/worksheets/sheet2.xml"));
        Element duracao = (Element) lista.item(Integer.parseInt(celula(junho, "C3").getAttribute("s")));
        assertThat(duracao.getAttribute("numFmtId")).isEqualTo("164");
        Element hora = (Element) lista.item(Integer.parseInt(celula(junho, "B3").getAttribute("s")));
        assertThat(hora.getAttribute("numFmtId")).isEqualTo("165");
        NodeList celulas = junho.getElementsByTagNameNS("*", "c");
        for (int i = 0; i < celulas.getLength(); i++) {
            assertThat(Integer.parseInt(((Element) celulas.item(i)).getAttribute("s"))).isLessThan(lista.getLength());
        }
    }

    @Test
    @DisplayName("Nome de aba e texto: sem os caracteres que o Excel e o XML não aceitam")
    void limpeza() {
        assertThat(EscritorXlsx.nomeDeAba("Jun/2026: [teste]?*")).isEqualTo("Jun 2026   teste");
        assertThat(EscritorXlsx.nomeDeAba("x".repeat(40))).hasSize(31);
        assertThat(EscritorXlsx.xml("a\u0001b\u000Bc\nd")).isEqualTo("abc\nd");
    }
}
