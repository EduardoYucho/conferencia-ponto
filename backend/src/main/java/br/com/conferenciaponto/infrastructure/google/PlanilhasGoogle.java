package br.com.conferenciaponto.infrastructure.google;

import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.Celula;
import br.com.conferenciaponto.application.planilha.Estilo;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaRemotaException;
import br.com.conferenciaponto.application.planilha.PlanilhasRemotas;
import br.com.conferenciaponto.infrastructure.google.GoogleSheetsApi.AbaRemota;
import br.com.conferenciaponto.infrastructure.google.GoogleSheetsApi.Metadados;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Publica a planilha de conferência no Google Sheets.
 *
 * <p>Cada aba do sistema tem um identificador fixo ({@link Aba#id()}): é por ele, e não pelo nome, que a aba é
 * reconhecida na planilha. Abas que não são do sistema ficam intactas (a pessoa que confere pode ter as
 * anotações dela na mesma planilha). A gravação de uma aba troca todo o conteúdo dela.
 */
@Component
public class PlanilhasGoogle implements PlanilhasRemotas {

    /** Tamanho aproximado de cada chamada de gravação (o Google recomenda ficar abaixo de 2 MB). */
    static final int BYTES_POR_CHAMADA = 1_200_000;
    /** Dia zero dos números de data do Google Sheets (30/12/1899) em relação a 01/01/1970. */
    private static final long DIAS_ATE_1970 = 25_569;
    private static final String AVISO_DE_EDICAO = "Aba atualizada automaticamente pelo sistema de conferência de "
            + "ponto: o que for digitado aqui é apagado na próxima atualização.";

    private static final Logger log = LoggerFactory.getLogger(PlanilhasGoogle.class);

    private final GoogleSheetsApi api;
    private final CredencialGoogle credencial;
    private final ObjectMapper json;

    public PlanilhasGoogle(GoogleSheetsApi api, CredencialGoogle credencial, ObjectMapper json) {
        this.api = api;
        this.credencial = credencial;
        this.json = json;
    }

    @Override
    public Optional<Conta> conta() {
        return credencial.chave().map(c -> new Conta(c.email(), c.projeto()));
    }

    @Override
    public Conta configurar(String chaveJson) {
        api.conferir(credencial.ler(chaveJson));
        CredencialGoogle.Chave gravada = credencial.gravar(chaveJson);
        return new Conta(gravada.email(), gravada.projeto());
    }

    @Override
    public void desconfigurar() {
        credencial.remover();
        api.esquecerToken();
    }

    @Override
    public String verificar(String planilhaId) {
        return api.metadados(planilhaId).titulo();
    }

    @Override
    public String publicar(String planilhaId, PlanilhaConferencia planilha, Set<Integer> abas) {
        Metadados remoto = api.metadados(planilhaId);
        boolean completa = abas == null;
        // uma aba do sistema que ainda não existe lá (mês novo, aba apagada) é sempre gravada: o resumo aponta para ela
        List<Aba> gravar = planilha.abas().stream()
                .filter(a -> completa || abas.contains(a.id()) || !remoto.tem(a.id()))
                .toList();
        for (Aba aba : gravar) {
            conferirNome(aba, remoto);
        }

        // 1) estrutura: cria as abas que faltam e ajusta nome e tamanho das que existem
        List<Map<String, Object>> estrutura = new ArrayList<>();
        for (Aba aba : gravar) {
            if (remoto.tem(aba.id())) {
                estrutura.add(Map.of("updateSheetProperties", Map.of(
                        "properties", propriedades(aba, null),
                        "fields", "title,gridProperties.rowCount,gridProperties.columnCount,gridProperties.frozenRowCount")));
            } else {
                estrutura.add(Map.of("addSheet", Map.of("properties", propriedades(aba, planilha.abas().indexOf(aba)))));
                estrutura.add(Map.of("addProtectedRange", Map.of("protectedRange", Map.of(
                        "range", Map.of("sheetId", aba.id()),
                        "description", AVISO_DE_EDICAO,
                        "warningOnly", true))));
            }
        }
        api.atualizar(planilhaId, estrutura);

        // 2) conteúdo: os meses primeiro e o resumo por último (as fórmulas dele apontam para as abas dos meses)
        List<Map<String, Object>> lote = new ArrayList<>();
        int bytes = 0;
        List<Aba> ordenadas = new ArrayList<>(gravar.stream().filter(a -> a.id() != Aba.ID_RESUMO).toList());
        gravar.stream().filter(a -> a.id() == Aba.ID_RESUMO).forEach(ordenadas::add);
        for (Aba aba : ordenadas) {
            List<Map<String, Object>> conteudo = conteudo(aba);
            int tamanho = tamanho(conteudo);
            if (!lote.isEmpty() && bytes + tamanho > BYTES_POR_CHAMADA) {
                api.atualizar(planilhaId, lote);
                lote = new ArrayList<>();
                bytes = 0;
            }
            lote.addAll(conteudo);
            bytes += tamanho;
        }

        // 3) limpeza: meses que deixaram de existir e, na primeira gravação, a aba vazia que vem na planilha nova
        if (completa) {
            for (AbaRemota r : remoto.abas()) {
                if (Aba.idDeMes(r.id()) && planilha.aba(r.id()).isEmpty()) {
                    lote.add(Map.of("deleteSheet", Map.of("sheetId", r.id())));
                }
            }
        }
        if (!remoto.tem(Aba.ID_RESUMO)) {
            List<AbaRemota> alheias = remoto.abas().stream()
                    .filter(r -> !Aba.idDeMes(r.id()) && r.id() != Aba.ID_RESUMO).toList();
            if (alheias.size() == 1 && api.vazia(planilhaId, alheias.get(0).titulo())) {
                lote.add(Map.of("deleteSheet", Map.of("sheetId", alheias.get(0).id())));
            }
        }
        api.atualizar(planilhaId, lote);
        log.debug("Planilha {} gravada: {} aba(s)", planilhaId, gravar.size());
        return remoto.titulo();
    }

    private static void conferirNome(Aba aba, Metadados remoto) {
        for (AbaRemota r : remoto.abas()) {
            if (r.id() != aba.id() && r.titulo().equalsIgnoreCase(aba.titulo())) {
                throw new PlanilhaRemotaException("GOOGLE_ABA_EM_CONFLITO",
                        "A planilha já tem uma aba chamada \"%s\" que não foi criada pelo sistema. Renomeie ou apague "
                                .formatted(aba.titulo()) + "essa aba, ou use uma planilha nova.", false);
            }
        }
    }

    private static Map<String, Object> propriedades(Aba aba, Integer indice) {
        Map<String, Object> grade = new LinkedHashMap<>();
        grade.put("rowCount", linhasDaGrade(aba));
        grade.put("columnCount", aba.colunas());
        grade.put("frozenRowCount", aba.linhasCongeladas());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sheetId", aba.id());
        p.put("title", aba.titulo());
        if (indice != null) {
            p.put("index", indice);
        }
        p.put("gridProperties", grade);
        return p;
    }

    /** Um respiro de duas linhas depois do conteúdo (e sempre mais linhas do que as congeladas). */
    private static int linhasDaGrade(Aba aba) {
        return Math.max(aba.linhas().size(), aba.linhasCongeladas()) + 2;
    }

    /** Requisições que trocam todo o conteúdo da aba: células, alinhamento vertical e largura das colunas. */
    static List<Map<String, Object>> conteudo(Aba aba) {
        List<Map<String, Object>> requisicoes = new ArrayList<>();
        List<Map<String, Object>> linhas = new ArrayList<>(aba.linhas().size());
        for (List<Celula> linha : aba.linhas()) {
            List<Map<String, Object>> valores = new ArrayList<>(linha.size());
            for (Celula c : linha) {
                valores.add(celula(c));
            }
            linhas.add(Map.of("values", valores));
        }
        Map<String, Object> intervalo = Map.of("sheetId", aba.id());
        // com o intervalo da aba inteira, o que não vier em "rows" é limpo
        requisicoes.add(Map.of("updateCells", Map.of(
                "range", intervalo,
                "rows", linhas,
                "fields", "userEnteredValue,userEnteredFormat")));
        requisicoes.add(Map.of("repeatCell", Map.of(
                "range", intervalo,
                "cell", Map.of("userEnteredFormat", Map.of("verticalAlignment", "TOP")),
                "fields", "userEnteredFormat.verticalAlignment")));
        for (int c = 0; c < aba.colunas(); c++) {
            requisicoes.add(Map.of("updateDimensionProperties", Map.of(
                    "range", Map.of("sheetId", aba.id(), "dimension", "COLUMNS", "startIndex", c, "endIndex", c + 1),
                    "properties", Map.of("pixelSize", aba.largurasPx().get(c)),
                    "fields", "pixelSize")));
        }
        return requisicoes;
    }

    static Map<String, Object> celula(Celula c) {
        Map<String, Object> celula = new LinkedHashMap<>();
        Map<String, Object> formato = formato(c.estilo());
        switch (c.tipo()) {
            case VAZIA -> { }
            case TEXTO -> celula.put("userEnteredValue", Map.of("stringValue", c.texto()));
            case DATA -> {
                celula.put("userEnteredValue", Map.of("numberValue", c.data().toEpochDay() + DIAS_ATE_1970));
                formato.put("numberFormat", Map.of("type", "DATE", "pattern", "dd/mm/yyyy"));
            }
            case HORA -> {
                celula.put("userEnteredValue", Map.of("numberValue", c.fracaoDoDia()));
                formato.put("numberFormat", Map.of("type", "TIME", "pattern", "hh:mm:ss"));
            }
            case DURACAO -> {
                celula.put("userEnteredValue", c.temFormula() ? Map.of("formulaValue", "=" + c.formula())
                        : Map.of("numberValue", c.fracaoDoDia()));
                formato.put("numberFormat", Map.of("type", "TIME", "pattern", "[h]:mm:ss"));
            }
            case INTEIRO -> {
                celula.put("userEnteredValue", c.temFormula() ? Map.of("formulaValue", "=" + c.formula())
                        : Map.of("numberValue", c.valor()));
                formato.put("numberFormat", Map.of("type", "NUMBER", "pattern", "0"));
            }
        }
        if (!formato.isEmpty()) {
            celula.put("userEnteredFormat", formato);
        }
        return celula;
    }

    private static Map<String, Object> formato(Estilo e) {
        Map<String, Object> formato = new LinkedHashMap<>();
        Map<String, Object> texto = new LinkedHashMap<>();
        if (e.negrito()) {
            texto.put("bold", true);
        }
        if (e.italico()) {
            texto.put("italic", true);
        }
        if (e.tamanho() > 0) {
            texto.put("fontSize", e.tamanho());
        }
        if (e.texto() != null) {
            texto.put("foregroundColorStyle", cor(e.texto()));
        }
        if (!texto.isEmpty()) {
            formato.put("textFormat", texto);
        }
        if (e.fundo() != null) {
            formato.put("backgroundColorStyle", cor(e.fundo()));
        }
        switch (e.alinhamento()) {
            case ESQUERDA -> formato.put("horizontalAlignment", "LEFT");
            case CENTRO -> formato.put("horizontalAlignment", "CENTER");
            case DIREITA -> formato.put("horizontalAlignment", "RIGHT");
            case PADRAO -> { }
        }
        if (e.quebra()) {
            formato.put("wrapStrategy", "WRAP");
        }
        if (e.bordaInferior()) {
            formato.put("borders", Map.of("bottom", Map.of("style", "SOLID", "colorStyle", cor(Estilo.Cor.SUAVE))));
        }
        return formato;
    }

    private static Map<String, Object> cor(Estilo.Cor cor) {
        return Map.of("rgbColor", Map.of("red", cor.vermelho(), "green", cor.verde(), "blue", cor.azul()));
    }

    private int tamanho(List<Map<String, Object>> requisicoes) {
        try {
            return json.writeValueAsBytes(requisicoes).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
