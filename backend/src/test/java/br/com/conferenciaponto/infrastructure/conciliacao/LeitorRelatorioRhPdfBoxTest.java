package br.com.conferenciaponto.infrastructure.conciliacao;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.OcorrenciaRh;
import br.com.conferenciaponto.domain.model.RelatorioRhLido;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O relatório real do RH tem CPF e não entra no repositório: o teste monta um PDF sintético com o
 * mesmo leiaute (colunas nas mesmas posições, dia da semana colado, continuação de linha, rodapé).
 */
class LeitorRelatorioRhPdfBoxTest {

    private final LeitorRelatorioRhPdfBox leitor = new LeitorRelatorioRhPdfBox();

    /** Texto em (x, linha): a linha n fica em y = 87 + 11,25 * n a partir do topo, como no original. */
    private record Texto(float x, int linha, String valor) {
    }

    private static byte[] pdf(List<List<Texto>> paginas) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PDType1Font fonte = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<Texto> textos : paginas) {
                PDPage pagina = new PDPage(PDRectangle.A4);
                doc.addPage(pagina);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                    for (Texto t : textos) {
                        cs.beginText();
                        cs.setFont(fonte, 8);
                        cs.newLineAtOffset(t.x(), PDRectangle.A4.getHeight() - (87 + 11.25f * t.linha()));
                        cs.showText(t.valor());
                        cs.endText();
                    }
                }
            }
            doc.save(saida);
            return saida.toByteArray();
        }
    }

    private static List<Texto> dia(int linha, String data, String horarios, String... duracoes) {
        return List.of(new Texto(74, linha, data), new Texto(141, linha, horarios),
                new Texto(319, linha, duracoes[0]), new Texto(386, linha, duracoes[1]), new Texto(453, linha, duracoes[2]));
    }

    private static byte[] relatorioExemplo() throws IOException {
        List<Texto> p1 = new java.util.ArrayList<>(List.of(
                new Texto(75, 0, "Empresa Exemplo Sistemas"), new Texto(427, 0, "14/09/2026 16:16:58"),
                new Texto(229, 6, "Relatório de Banco de Horas"),
                new Texto(75, 10, "Funcionário: 000099 - Pessoa de Teste"),
                new Texto(75, 11, "Período: 01/08/2026 à 31/08/2026"),
                new Texto(75, 12, "Carga Horária: 2 - 08:00 - 12:00 - 13:00 17:48   Tolerância: 5 minutos"),
                new Texto(74, 15, "Data"), new Texto(141, 15, "Horários"), new Texto(319, 15, "Hr. Trabalho"),
                new Texto(386, 15, "Hr. Trabalhadas"), new Texto(453, 15, "Hr. Extra/Falta")));
        p1.addAll(dia(17, "01/08/2026 sáb", "Férias", "00:00:00", "00:00:00", "00:00:00"));
        p1.addAll(dia(18, "02/08/2026", "Férias", "00:00:00", "00:00:00", "00:00:00"));
        p1.add(new Texto(74, 19, "dom"));
        p1.addAll(dia(20, "10/08/2026", "seg07:57:37 12:00:41 - 12:55:55 17:50:58", "08:48:00", "08:58:07", "00:00:00"));
        p1.addAll(dia(21, "11/08/2026 ter", "08:03:33 11:01:37 - 11:15:45 12:00:21 -", "08:48:00", "08:46:39", "00:01:51"));
        p1.add(new Texto(141, 22, "12:49:38 17:53:37"));
        p1.addAll(dia(23, "12/08/2026 qua", "08:04:22 12:18:36", "08:48:00", "04:14:14", "-04:29:24"));
        p1.add(new Texto(300, 30, "1"));
        List<Texto> p2 = new java.util.ArrayList<>(List.of(new Texto(74, 0, "13/08/2026 qui"), new Texto(141, 0, "Feriado"),
                new Texto(319, 0, "00:00:00"), new Texto(386, 0, "00:00:00"), new Texto(453, 0, "00:00:00"),
                new Texto(74, 1, "14/08/2026 sexfolga aniversár"), new Texto(319, 1, "00:00:00"),
                new Texto(386, 1, "00:00:00"), new Texto(453, 1, "00:00:00"),
                new Texto(74, 2, "Total:"), new Texto(319, 2, "1073:36:00"), new Texto(386, 2, "21:59:00"),
                new Texto(453, 2, "-04:27:33"),
                new Texto(75, 6, "Pessoa de Teste"), new Texto(75, 7, "CPF: 000.000.000-00")));
        return pdf(List.of(p1, p2));
    }

    @Test
    @DisplayName("Lê cabeçalho, dias (com continuação e dia da semana colado), ocorrências e totais")
    void leRelatorio() throws IOException {
        RelatorioRhLido r = leitor.ler(relatorioExemplo());

        assertThat(r.emitidoEm()).isEqualTo(LocalDateTime.of(2026, 9, 14, 16, 16, 58));
        assertThat(r.periodoInicio()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(r.periodoFim()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(r.funcionario()).isEqualTo("000099 - Pessoa de Teste");
        assertThat(r.totalPrevistoSegundos()).isEqualTo(1073 * 3600 + 36 * 60);
        assertThat(r.totalSaldoSegundos()).isEqualTo(-(4 * 3600 + 27 * 60 + 33));
        assertThat(r.dias()).extracting(DiaRelatorioRh::data).containsExactly(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 14));

        DiaRelatorioRh domingo = r.dias().get(1);
        assertThat(domingo.ocorrencia()).isEqualTo("Férias");
        assertThat(domingo.tipoOcorrencia()).contains(OcorrenciaRh.FERIAS);

        DiaRelatorioRh colado = r.dias().get(2);
        assertThat(colado.horarios()).containsExactly(LocalTime.of(7, 57, 37), LocalTime.of(12, 0, 41),
                LocalTime.of(12, 55, 55), LocalTime.of(17, 50, 58));
        assertThat(colado.ocorrencia()).isNull();
        assertThat(colado.jornadaPrevistaSegundos()).isEqualTo(31_680);

        DiaRelatorioRh seisBatidas = r.dias().get(3);
        assertThat(seisBatidas.horarios()).hasSize(6).last().isEqualTo(LocalTime.of(17, 53, 37));
        assertThat(seisBatidas.saldoSegundos()).isEqualTo(111);

        assertThat(r.dias().get(4).saldoSegundos()).isEqualTo(-(4 * 3600 + 29 * 60 + 24));
        assertThat(r.dias().get(5).tipoOcorrencia()).contains(OcorrenciaRh.FERIADO);
        assertThat(r.dias().get(6).tipoOcorrencia()).contains(OcorrenciaRh.FOLGA);
        assertThat(r.dias()).extracting(DiaRelatorioRh::ocorrencia).filteredOn(o -> o != null)
                .noneMatch(o -> o.contains("CPF") || o.contains("000.000"));
    }

    @Test
    @DisplayName("PDF que não é o relatório de banco de horas é recusado com mensagem clara")
    void recusaOutroPdf() throws IOException {
        byte[] comprovante = pdf(List.of(List.of(new Texto(75, 0, "Comprovante de Ponto - 28/09/2026 08:02:31"))));

        assertThatThrownBy(() -> leitor.ler(comprovante))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("Relatório de Banco de Horas");
        assertThatThrownBy(() -> leitor.ler(new byte[]{1, 2, 3}))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("não é um PDF válido");
    }

    @Test
    void converteDuracoes() {
        assertThat(LeitorRelatorioRhPdfBox.segundos("08:48:00")).isEqualTo(31_680);
        assertThat(LeitorRelatorioRhPdfBox.segundos("-04:48:00")).isEqualTo(-17_280);
        assertThat(LeitorRelatorioRhPdfBox.segundos("1073:36:00")).isEqualTo(3_864_960);
    }
}
