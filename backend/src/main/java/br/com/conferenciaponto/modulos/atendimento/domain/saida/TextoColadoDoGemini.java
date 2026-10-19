package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O texto que o app do Gemini escreveu com a habilidade do sistema, colado de volta pela pessoa, lido para a mesma
 * conferência da API. É texto para ir direto ao chamado (o formato está nas instruções da habilidade): a linha
 * "Assunto:", nos chamados a linha "Tela/Funcionalidade:", as partes com "## Título", parágrafos separados por linha
 * em branco e as marcações de imagem {@code [Imagem A3: legenda]} (ou {@code [Imagem A4@01:15: legenda]}, um momento
 * do vídeo). Nas partes de passos, cada linha é um passo. Os dados para a base ("Cliente:", "Módulo:"...) entram se
 * vierem colados junto. O Markdown que o app costuma pôr (negrito, títulos, marcadores, números) é tirado.
 */
public final class TextoColadoDoGemini {

    private static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern ASSUNTO = Pattern.compile("^assunto\\s*:\\s*(.*)$", CI);
    private static final Pattern TELA = Pattern.compile("^tela\\s*/\\s*funcionalidade\\s*:\\s*(.*)$", CI);
    private static final Pattern IMAGEM = Pattern.compile(
            "\\[\\s*imagem\\s+([A-Za-z]{1,2}\\s*0*\\d{1,5}(?:\\s*@\\s*\\d{1,3}(?::\\d{2}){1,2})?)\\s*(?:[:\\-–—]\\s*([^\\]]*))?]", CI);
    private static final Pattern NUMERADO = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern MARCADOR = Pattern.compile("^[-*•+]\\s+(.*)$");
    private static final Pattern TITULO = Pattern.compile("^#{1,6}\\s*(.+?)\\s*#*$");
    private static final Pattern NEGRITO_SOZINHO = Pattern.compile("^(?:\\*\\*|__)(.+?)(?:\\*\\*|__):?$");
    private static final Pattern SEPARADOR = Pattern.compile("^(?:-{3,}|\\*{3,}|_{3,})$");
    private static final Pattern CERCA = Pattern.compile("^```.*$");
    private static final Pattern ROTULO = Pattern.compile("^([^:]{2,30}):\\s*(.*)$");
    /** "(M12, A3)" no fim de um trecho: o Gemini não devia escrever, mas, se escrever, viram as fontes do trecho. */
    private static final Pattern FONTES = Pattern.compile("\\s*[(\\[]\\s*((?:[MALPV]\\d{1,5}\\s*[,;e]?\\s*)+)[)\\]]");
    private static final Pattern ID = Pattern.compile("[MALPV]\\d{1,5}");
    private static final Map<String, String> CAMPOS_DA_BASE = Map.of(
            "cliente", "cliente", "modulo", "modulo", "tela", "tela", "codigo da tela", "codigoTela",
            "sintoma", "sintoma", "mensagem de erro", "mensagemErro", "causa", "causa", "resolucao", "resolucao");
    private static final Set<String> TITULOS_IGNORADOS = Set.of("solicitacao de melhoria", "resumo do atendimento",
            "resumo", "chamado de erro", "chamado de alteracao", "chamado de erro para o desenvolvimento",
            "chamado de alteracao para o desenvolvimento", "texto");

    private TextoColadoDoGemini() {
    }

    /** Lê o texto colado (o que não for reconhecido vira parágrafo; nada se perde). */
    public static ConferenciaDoTexto.Bruto ler(String colado, ModeloDoTexto modelo) {
        List<String> linhas = new ArrayList<>();
        for (String linha : (colado == null ? "" : colado).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String limpa = linha.replace(' ', ' ').replace("\t", "    ").strip();
            if (!CERCA.matcher(limpa).matches()) {
                linhas.add(limpa.replaceFirst("^>\\s?", ""));
            }
        }
        Set<Integer> daBase = linhasDaBase(linhas);
        Map<String, String> titulos = new LinkedHashMap<>();
        modelo.secoes().forEach(s -> titulos.put(normalizar(s.titulo()), s.chave()));
        String padrao = modelo.secao("relato").or(() -> modelo.secao("descricao"))
                .map(ModeloDoTexto.Secao::chave).orElse(modelo.secoes().isEmpty() ? null : modelo.secoes().get(0).chave());

        Set<String> dePassos = new HashSet<>();
        modelo.secoes().stream().filter(ModeloDoTexto.Secao::passos).forEach(sec -> dePassos.add(sec.chave()));
        Leitura leitura = new Leitura(modelo.comSecoes() ? padrao : null, dePassos);
        String assunto = null;
        String tela = null;
        Map<String, String> base = new LinkedHashMap<>();
        boolean naBase = false;
        for (int i = 0; i < linhas.size(); i++) {
            String linha = linhas.get(i);
            if (linha.isEmpty() || SEPARADOR.matcher(linha).matches()) {
                leitura.fechar();
                continue;
            }
            String titulo = titulo(linha);
            String semEnfeite = semEnfeites(titulo != null ? titulo : linha);
            if (assunto == null && ASSUNTO.matcher(semEnfeite).matches()) {
                Matcher m = ASSUNTO.matcher(semEnfeite);
                m.matches();
                assunto = m.group(1).strip();
                continue;
            }
            Matcher mTela = TELA.matcher(semEnfeite);
            if (mTela.matches()) {
                tela = mTela.group(1).strip();
                continue;
            }
            if (titulo != null) {
                String normal = normalizar(titulo);
                String chave = chaveDaSecao(normal, titulos);
                if (chave != null && modelo.comSecoes()) {
                    leitura.secao(chave);
                    naBase = false;
                    continue;
                }
                if (normal.contains("base de conhecimento") || normal.startsWith("dados para a base")) {
                    leitura.fechar();
                    naBase = true;
                    continue;
                }
                if (TITULOS_IGNORADOS.contains(normal)) {
                    leitura.fechar();
                    continue;
                }
            } else if (TITULOS_IGNORADOS.contains(normalizar(semEnfeite))) {
                continue; // "Solicitação de Melhoria" sozinha: o sistema escreve a abertura
            }
            if (daBase.contains(i) || naBase) {
                Matcher rotulo = ROTULO.matcher(semEnfeites(MARCADOR.matcher(linha).replaceFirst("$1")));
                String campo = rotulo.matches() ? CAMPOS_DA_BASE.get(normalizar(rotulo.group(1))) : null;
                if (campo != null) {
                    base.put(campo, rotulo.group(2).strip());
                    continue;
                }
                naBase = false;
            }
            if (titulo != null) {
                leitura.fechar(); // um título que não é de parte vira um parágrafo só dele
                leitura.linha(titulo);
                leitura.fechar();
            } else {
                leitura.linha(linha);
            }
        }
        leitura.terminar();
        return new ConferenciaDoTexto.Bruto(assunto == null ? "" : assunto,
                modelo.comSecoes() ? modelo.ordenar(leitura.porSecao) : leitura.porSecao.getOrDefault(null, List.of()),
                base, tela == null ? "" : tela, List.of());
    }

    /** As linhas "Cliente: ...", "Módulo: ..." seguidas (pelo menos três): os dados para a base. */
    private static Set<Integer> linhasDaBase(List<String> linhas) {
        Set<Integer> daBase = new HashSet<>();
        int inicio = -1;
        for (int i = 0; i <= linhas.size(); i++) {
            boolean campo = false;
            if (i < linhas.size()) {
                Matcher rotulo = ROTULO.matcher(semEnfeites(MARCADOR.matcher(linhas.get(i)).replaceFirst("$1")));
                campo = rotulo.matches() && CAMPOS_DA_BASE.containsKey(normalizar(rotulo.group(1)));
            }
            if (campo && inicio < 0) {
                inicio = i;
            } else if (!campo && inicio >= 0) {
                if (i - inicio >= 3) {
                    for (int j = inicio; j < i; j++) {
                        daBase.add(j);
                    }
                }
                inicio = -1;
            }
        }
        return daBase;
    }

    private static String chaveDaSecao(String normal, Map<String, String> titulos) {
        String exata = titulos.get(normal);
        if (exata != null) {
            return exata;
        }
        return titulos.entrySet().stream().filter(t -> normal.startsWith(t.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    /** "## Título", "**Título**" ou "**Título:**" numa linha sozinha. */
    private static String titulo(String linha) {
        Matcher m = TITULO.matcher(linha);
        if (m.matches()) {
            return semEnfeites(m.group(1)).replaceAll(":$", "").strip();
        }
        m = NEGRITO_SOZINHO.matcher(linha);
        if (m.matches() && !m.group(1).contains("**")) {
            return m.group(1).replaceAll(":$", "").strip();
        }
        return null;
    }

    /** Sem o negrito, o itálico e o código do Markdown. */
    static String semEnfeites(String texto) {
        return texto.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("__(.+?)__", "$1")
                .replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "$1").replaceAll("`([^`]*)`", "$1").strip();
    }

    /** Minúsculas, sem acento, sem a pontuação do fim e com os espaços normais. */
    static String normalizar(String texto) {
        String sem = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sem.toLowerCase(Locale.ROOT).replaceAll("[:.\\s]+$", "").replaceAll("\\s+", " ").strip();
    }

    /** Os trechos sendo montados: o parágrafo ou o item de lista aberto, e as imagens que ainda não têm trecho. */
    private static final class Leitura {

        final Map<String, List<ConferenciaDoTexto.BlocoBruto>> porSecao = new LinkedHashMap<>();
        private final Set<String> dePassos;
        private String secao;
        private String tipo;
        private StringBuilder texto;
        private final List<ConferenciaDoTexto.ImagemBruta> imagens = new ArrayList<>();
        private final List<ConferenciaDoTexto.ImagemBruta> semTrecho = new ArrayList<>();

        Leitura(String secao, Set<String> dePassos) {
            this.secao = secao;
            this.dePassos = dePassos;
        }

        void secao(String chave) {
            fechar();
            pendurarNoUltimo();
            secao = chave;
        }

        void linha(String linha) {
            List<ConferenciaDoTexto.ImagemBruta> daLinha = new ArrayList<>();
            Matcher m = IMAGEM.matcher(linha);
            StringBuilder resto = new StringBuilder();
            while (m.find()) {
                String legenda = m.group(2) == null ? "" : semEnfeites(m.group(2));
                daLinha.add(new ConferenciaDoTexto.ImagemBruta(m.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT), legenda));
                m.appendReplacement(resto, " ");
            }
            m.appendTail(resto);
            String sobra = resto.toString().strip();
            if (sobra.isEmpty() || sobra.matches("[-–—:.,;]+")) {
                // a imagem numa linha sozinha ilustra o trecho de antes
                if (texto != null) {
                    imagens.addAll(daLinha);
                } else if (!pendurar(daLinha)) {
                    semTrecho.addAll(daLinha);
                }
                return;
            }
            Matcher numerado = NUMERADO.matcher(sobra);
            Matcher marcador = MARCADOR.matcher(sobra);
            if (numerado.matches()) {
                abrir("passo", numerado.group(2));
            } else if (marcador.matches()) {
                abrir("paragrafo", marcador.group(1));
            } else if (texto != null && !dePassos.contains(secao)) {
                texto.append(' ').append(sobra);
            } else {
                abrir("paragrafo", sobra);
            }
            imagens.addAll(daLinha);
        }

        private void abrir(String novoTipo, String inicio) {
            fechar();
            tipo = novoTipo;
            texto = new StringBuilder(inicio);
            imagens.addAll(semTrecho);
            semTrecho.clear();
        }

        void fechar() {
            if (texto == null) {
                return;
            }
            String limpo = semEnfeites(texto.toString()).replaceAll("\\s+([,.;:!?])", "$1").replaceAll("\\s{2,}", " ").strip();
            List<String> fontes = new ArrayList<>();
            Matcher f = FONTES.matcher(limpo);
            StringBuilder semFontes = new StringBuilder();
            while (f.find()) {
                Matcher id = ID.matcher(f.group(1));
                while (id.find()) {
                    fontes.add(id.group());
                }
                f.appendReplacement(semFontes, "");
            }
            f.appendTail(semFontes);
            limpo = semFontes.toString().replaceAll("\\s+([,.;:!?])", "$1").strip();
            porSecao.computeIfAbsent(secao, s -> new ArrayList<>())
                    .add(new ConferenciaDoTexto.BlocoBruto(tipo, limpo, fontes, List.copyOf(imagens), secao));
            texto = null;
            imagens.clear();
        }

        /** Imagem numa linha sozinha logo depois de um trecho já fechado (havia uma linha em branco entre eles). */
        private boolean pendurar(List<ConferenciaDoTexto.ImagemBruta> novas) {
            List<ConferenciaDoTexto.BlocoBruto> daSecao = porSecao.get(secao);
            if (daSecao == null || daSecao.isEmpty()) {
                return false;
            }
            ConferenciaDoTexto.BlocoBruto ultimo = daSecao.remove(daSecao.size() - 1);
            List<ConferenciaDoTexto.ImagemBruta> todas = new ArrayList<>(ultimo.imagens());
            todas.addAll(novas);
            daSecao.add(new ConferenciaDoTexto.BlocoBruto(ultimo.tipo(), ultimo.texto(), ultimo.fontes(), todas, ultimo.secao()));
            return true;
        }

        /** Imagens que sobraram sem trecho no fim de uma parte ficam no último trecho dela, ou num trecho só delas. */
        private void pendurarNoUltimo() {
            if (semTrecho.isEmpty()) {
                return;
            }
            if (!pendurar(List.copyOf(semTrecho))) {
                porSecao.computeIfAbsent(secao, s -> new ArrayList<>())
                        .add(new ConferenciaDoTexto.BlocoBruto("paragrafo", "", List.of(), List.copyOf(semTrecho), secao));
            }
            semTrecho.clear();
        }

        void terminar() {
            fechar();
            pendurarNoUltimo();
        }
    }
}
