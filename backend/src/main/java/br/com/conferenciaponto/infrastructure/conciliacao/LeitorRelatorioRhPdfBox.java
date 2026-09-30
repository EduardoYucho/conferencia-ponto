package br.com.conferenciaponto.infrastructure.conciliacao;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRhLido;
import br.com.conferenciaponto.domain.port.LeitorRelatorioRh;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o "Relatório de Banco de Horas" do sistema de ponto do RH com Apache PDFBox, pela
 * posição do texto na página:
 * <pre>
 * Data       Horários                                      Hr. Trabalho  Hr. Trabalhadas  Hr. Extra/Falta
 * 10/08/2026 seg07:57:37 12:00:41 - 12:55:55 17:50:58     08:48:00      08:58:07         00:00:00
 * 02/08/2026 Férias                                        00:00:00      00:00:00         00:00:00
 * dom
 * </pre>
 * <ul>
 *   <li>As três durações ficam à direita da coluna "Hr."; o resto são batidas (até 3 pares por linha,
 *       com continuação na linha de baixo quando há mais).</li>
 *   <li>O dia da semana vem colado no primeiro horário ("seg07:57:37") ou na linha de baixo ("dom").</li>
 *   <li>Ocorrências (Feriado, Férias, folga aniversário) aparecem no lugar das batidas.</li>
 *   <li>O CPF do rodapé é ignorado: nada fora da tabela é guardado além de funcionário, emissão,
 *       período e totais.</li>
 * </ul>
 */
@Component
public class LeitorRelatorioRhPdfBox implements LeitorRelatorioRh {

    private static final Pattern DATA_INICIO = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})(.*)$");
    private static final Pattern HORARIO = Pattern.compile("-?\\d{2,4}:\\d{2}:\\d{2}");
    private static final Pattern EMISSAO = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern PERIODO = Pattern.compile(
            "Per[ií]odo:\\s*(\\d{2}/\\d{2}/\\d{4})\\s*(?:à|a|at[ée])\\s*(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern FUNCIONARIO = Pattern.compile("Funcion[áa]rio:\\s*(.+)$");
    private static final Pattern DIA_SEMANA = Pattern.compile("(?i)^(seg|ter|qua|qui|sex|s[áa]b|dom)");
    private static final DateTimeFormatter DATA =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    /** Distância vertical máxima (pt) entre a linha do dia e a sua continuação. */
    private static final float CONTINUACAO_MAXIMA = 14f;

    record Palavra(String texto, List<Float> xs, float y, int pagina) {
        float x() {
            return xs.get(0);
        }

        /** Posição horizontal do caractere (a palavra pode juntar dia da semana e horário). */
        float xDoCaractere(int indice) {
            return indice < xs.size() ? xs.get(indice) : x();
        }
    }

    record Linha(List<Palavra> palavras, float y, int pagina) {
        String texto() {
            return String.join(" ", palavras.stream().map(Palavra::texto).toList()).strip();
        }
    }

    @Override
    public RelatorioRhLido ler(byte[] pdf) {
        List<Linha> linhas;
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            ColetorDePalavras coletor = new ColetorDePalavras();
            coletor.getText(documento);
            linhas = agrupar(coletor.palavras);
        } catch (InvalidPasswordException e) {
            throw invalido("O PDF está protegido por senha.");
        } catch (IOException e) {
            throw invalido("Não foi possível ler o PDF: " + e.getMessage());
        }
        return interpretar(linhas);
    }

    RelatorioRhLido interpretar(List<Linha> linhas) {
        LocalDateTime emissao = null;
        LocalDate inicio = null;
        LocalDate fim = null;
        String funcionario = null;
        Float colunaDuracoes = null;
        boolean naTabela = false;
        Integer[] totais = null;
        List<DiaEmMontagem> dias = new ArrayList<>();
        DiaEmMontagem atual = null;

        for (Linha linha : linhas) {
            String texto = linha.texto();
            if (!naTabela) {
                Matcher m;
                if (emissao == null && (m = EMISSAO.matcher(texto)).find()) {
                    emissao = LocalDateTime.of(data(m.group(1)), LocalTime.parse(m.group(2), HORA));
                } else if (inicio == null && (m = PERIODO.matcher(texto)).find()) {
                    inicio = data(m.group(1));
                    fim = data(m.group(2));
                } else if (funcionario == null && (m = FUNCIONARIO.matcher(texto)).find()) {
                    funcionario = m.group(1).strip();
                } else if (texto.startsWith("Data") && texto.contains("Hor")) {
                    naTabela = true;
                    colunaDuracoes = linha.palavras().stream().filter(p -> p.texto().startsWith("Hr"))
                            .map(Palavra::x).min(Float::compare).orElse(null);
                }
                continue;
            }
            if (texto.startsWith("Total")) {
                totais = duracoes(texto);
                break;
            }
            Matcher inicioDia = DATA_INICIO.matcher(linha.palavras().get(0).texto());
            if (inicioDia.matches()) {
                DiaEmMontagem dia = new DiaEmMontagem(data(inicioDia.group(1)), linha);
                separar(linha, colunaDuracoes, dia, true);
                if (dia.duracoes.size() >= 3) {
                    dias.add(dia);
                    atual = dia;
                    continue;
                }
                atual = null; // cabeçalho repetido (ex.: data/hora de emissão numa página nova)
                continue;
            }
            if (atual != null && linha.pagina() == atual.ultimaLinha.pagina()
                    && linha.y() - atual.ultimaLinha.y() <= CONTINUACAO_MAXIMA) {
                separar(linha, colunaDuracoes, atual, false);
                atual.ultimaLinha = linha;
            }
        }

        if (emissao == null || inicio == null || !naTabela) {
            throw invalido("O arquivo não parece um \"Relatório de Banco de Horas\" do RH (emissão, período ou tabela não encontrados).");
        }
        if (dias.isEmpty()) {
            throw invalido("Nenhum dia encontrado na tabela do relatório.");
        }
        Map<LocalDate, DiaRelatorioRh> porData = new LinkedHashMap<>();
        for (DiaEmMontagem d : dias) {
            porData.put(d.data, d.montar());
        }
        return new RelatorioRhLido(funcionario, emissao, inicio, fim,
                totais == null ? null : totais[0], totais == null ? null : totais[1], totais == null ? null : totais[2],
                List.copyOf(porData.values()));
    }

    /** Separa horários (batidas × durações) e textos (dia da semana × ocorrência) de uma linha. */
    private static void separar(Linha linha, Float colunaDuracoes, DiaEmMontagem dia, boolean primeira) {
        List<String> horariosDaLinha = new ArrayList<>();
        List<Boolean> ehDuracao = new ArrayList<>();
        for (int i = 0; i < linha.palavras().size(); i++) {
            Palavra p = linha.palavras().get(i);
            String texto = p.texto();
            if (primeira && i == 0) {
                Matcher m = DATA_INICIO.matcher(texto);
                if (m.matches()) {
                    int deslocamento = Math.min(texto.length() - m.group(2).length(), p.xs().size() - 1);
                    texto = m.group(2);
                    p = new Palavra(texto, p.xs().subList(deslocamento, p.xs().size()), p.y(), p.pagina());
                }
            }
            Matcher h = HORARIO.matcher(texto);
            StringBuilder sobra = new StringBuilder();
            int ultimo = 0;
            while (h.find()) {
                sobra.append(texto, ultimo, h.start()).append(' ');
                ultimo = h.end();
                horariosDaLinha.add(h.group());
                ehDuracao.add(colunaDuracoes != null && p.xDoCaractere(h.start()) >= colunaDuracoes - 3);
            }
            sobra.append(texto.substring(ultimo));
            dia.textos(sobra.toString());
        }
        if (colunaDuracoes == null && primeira) { // sem cabeçalho de colunas: as 3 últimas são as durações
            for (int i = 0; i < ehDuracao.size(); i++) {
                ehDuracao.set(i, i >= ehDuracao.size() - 3);
            }
        }
        for (int i = 0; i < horariosDaLinha.size(); i++) {
            (ehDuracao.get(i) ? dia.duracoes : dia.batidas).add(horariosDaLinha.get(i));
        }
    }

    private static final class DiaEmMontagem {
        final LocalDate data;
        final List<String> batidas = new ArrayList<>();
        final List<String> duracoes = new ArrayList<>();
        final List<String> ocorrencia = new ArrayList<>();
        Linha ultimaLinha;

        DiaEmMontagem(LocalDate data, Linha linha) {
            this.data = data;
            this.ultimaLinha = linha;
        }

        void textos(String sobra) {
            for (String parte : sobra.replace('-', ' ').strip().split("\\s+")) {
                String semDia = DIA_SEMANA.matcher(parte).replaceFirst("").strip();
                if (!semDia.isEmpty() && !semDia.chars().allMatch(Character::isDigit)) {
                    ocorrencia.add(semDia);
                }
            }
        }

        DiaRelatorioRh montar() {
            List<LocalTime> horarios = new ArrayList<>();
            for (String b : batidas) {
                try {
                    horarios.add(LocalTime.parse(b, HORA));
                } catch (DateTimeParseException e) {
                    throw invalido("Horário inválido em %s: %s".formatted(DATA.format(data), b));
                }
            }
            int n = duracoes.size();
            return new DiaRelatorioRh(data, horarios, ocorrencia.isEmpty() ? null : String.join(" ", ocorrencia),
                    segundos(duracoes.get(n - 3)), segundos(duracoes.get(n - 2)), segundos(duracoes.get(n - 1)));
        }
    }

    private static Integer[] duracoes(String linhaTotal) {
        List<String> valores = new ArrayList<>();
        Matcher m = HORARIO.matcher(linhaTotal);
        while (m.find()) {
            valores.add(m.group());
        }
        if (valores.size() < 3) {
            return null;
        }
        int n = valores.size();
        return new Integer[]{segundos(valores.get(n - 3)), segundos(valores.get(n - 2)), segundos(valores.get(n - 1))};
    }

    /** "08:48:00" → 31680; "-04:48:00" → -17280; "1073:36:00" → 3864960. */
    static int segundos(String duracao) {
        boolean negativo = duracao.startsWith("-");
        String[] partes = (negativo ? duracao.substring(1) : duracao).split(":");
        int total = Integer.parseInt(partes[0]) * 3600 + Integer.parseInt(partes[1]) * 60 + Integer.parseInt(partes[2]);
        return negativo ? -total : total;
    }

    private static LocalDate data(String texto) {
        try {
            return LocalDate.parse(texto, DATA);
        } catch (DateTimeParseException e) {
            throw invalido("Data inválida no relatório: " + texto);
        }
    }

    private static RegraNegocioException invalido(String mensagem) {
        return new RegraNegocioException("RELATORIO_RH_INVALIDO", mensagem);
    }

    /** Agrupa as palavras em linhas (mesma página e mesma altura, com folga de 2 pt), da esquerda para a direita. */
    static List<Linha> agrupar(List<Palavra> palavras) {
        List<Palavra> ordenadas = new ArrayList<>(palavras);
        ordenadas.sort(Comparator.comparingInt(Palavra::pagina).thenComparing(Palavra::y).thenComparing(Palavra::x));
        List<Linha> linhas = new ArrayList<>();
        List<Palavra> atual = new ArrayList<>();
        Palavra referencia = null;
        for (Palavra p : ordenadas) {
            if (referencia != null && (p.pagina() != referencia.pagina() || Math.abs(p.y() - referencia.y()) > 2f)) {
                linhas.add(fechar(atual));
                atual = new ArrayList<>();
            }
            if (atual.isEmpty()) {
                referencia = p;
            }
            atual.add(p);
        }
        if (!atual.isEmpty()) {
            linhas.add(fechar(atual));
        }
        return linhas;
    }

    private static Linha fechar(List<Palavra> palavras) {
        List<Palavra> ordenadas = new ArrayList<>(palavras);
        ordenadas.sort(Comparator.comparing(Palavra::x));
        return new Linha(List.copyOf(ordenadas), ordenadas.get(0).y(), ordenadas.get(0).pagina());
    }

    /** Captura cada palavra com a posição dos seus caracteres. */
    private static final class ColetorDePalavras extends PDFTextStripper {
        final List<Palavra> palavras = new ArrayList<>();

        ColetorDePalavras() {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String texto, List<TextPosition> posicoes) {
            if (texto == null || texto.isBlank() || posicoes.isEmpty()) {
                return;
            }
            dividirPorEspaco(texto, posicoes);
        }

        /** O PDFBox já entrega palavra a palavra; por segurança, divide em espaços internos. */
        private void dividirPorEspaco(String texto, List<TextPosition> posicoes) {
            int inicio = 0;
            for (int i = 0; i <= texto.length(); i++) {
                if (i == texto.length() || Character.isWhitespace(texto.charAt(i))) {
                    if (i > inicio) {
                        String parte = texto.substring(inicio, i);
                        List<Float> xs = new ArrayList<>();
                        for (int j = inicio; j < i && j < posicoes.size(); j++) {
                            xs.add(posicoes.get(j).getXDirAdj());
                        }
                        if (xs.isEmpty()) {
                            xs.add(posicoes.get(Math.min(inicio, posicoes.size() - 1)).getXDirAdj());
                        }
                        TextPosition primeira = posicoes.get(Math.min(inicio, posicoes.size() - 1));
                        palavras.add(new Palavra(parte, xs, primeira.getYDirAdj(), getCurrentPageNo()));
                    }
                    inicio = i + 1;
                }
            }
        }
    }
}
