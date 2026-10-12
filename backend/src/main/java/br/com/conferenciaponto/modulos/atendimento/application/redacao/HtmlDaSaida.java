package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.ImagemDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MontagemDoHtml;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O HTML de uma saída depois de montado: a limpeza do que a pessoa editou (jsoup, com lista de tags permitidas), a
 * numeração das imagens na ordem em que aparecem no texto e as formas de copiar para o sistema de chamados.
 */
final class HtmlDaSaida {

    /** O que o editor do texto pode deixar: formatação simples, listas e os marcadores de fonte e de imagem. */
    private static final Safelist PERMITIDAS = Safelist.none()
            .addTags("p", "br", "strong", "b", "em", "i", "u", "s", "ol", "ul", "li", "blockquote", "h3", "h4")
            .addAttributes("p", "data-fontes", "data-imagem")
            .addAttributes("li", "data-fontes");
    private static final Pattern IDENTIFICADOR = Pattern.compile("^[MALPV]\\d{1,5}$");
    private static final Pattern ROTULO = Pattern.compile("^\\s*imagem\\s*\\d*\\s*[:\\-–—]?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int LIMITE_DA_LEGENDA = 300;

    private HtmlDaSaida() {
    }

    /** O HTML editado sem nada fora da lista (script, style, onclick, links, imagens coladas...). */
    static String limpar(String html) {
        Document.OutputSettings saida = new Document.OutputSettings().prettyPrint(false);
        Document doc = fragmento(Jsoup.clean(html == null ? "" : html, "", PERMITIDAS, saida));
        for (Element e : doc.select("[data-fontes]")) {
            String fontes = Arrays.stream(e.attr("data-fontes").trim().split("\\s+"))
                    .filter(f -> IDENTIFICADOR.matcher(f).matches())
                    .distinct()
                    .collect(Collectors.joining(" "));
            e.attr("data-fontes", fontes);
        }
        for (Element e : doc.select("[data-imagem]")) {
            if (uuid(e.attr("data-imagem")) == null) {
                e.removeAttr("data-imagem");
            }
        }
        return corpo(doc);
    }

    /** Sobrou algum texto (ou imagem) depois da limpeza. */
    static boolean temConteudo(String html) {
        Document doc = fragmento(html);
        return !doc.body().text().isBlank();
    }

    /** O HTML com as imagens numeradas na ordem do texto, e a numeração para gravar. */
    record Renumerado(String html, List<Saidas.ImagemNumerada> ordem) {
    }

    /**
     * Numera as imagens na ordem em que aparecem: o parágrafo de cada uma vira "Imagem N: legenda" (a legenda é o
     * texto que a pessoa deixou depois do rótulo). Imagem repetida ou que não é da saída sai do texto; as que não
     * estão no texto ficam de fora (numeradas depois das incluídas, para poderem voltar).
     */
    static Renumerado renumerar(String html, List<ImagemDaSaida> imagens) {
        Map<UUID, ImagemDaSaida> porId = new LinkedHashMap<>();
        imagens.forEach(i -> porId.put(i.id(), i));
        Document doc = fragmento(html);
        List<Saidas.ImagemNumerada> ordem = new ArrayList<>();
        Set<UUID> vistas = new HashSet<>();
        int numero = 0;
        for (Element p : doc.select("p[data-imagem]")) {
            UUID id = uuid(p.attr("data-imagem"));
            ImagemDaSaida imagem = id == null ? null : porId.get(id);
            if (imagem == null || !vistas.add(id)) {
                p.remove();
                continue;
            }
            numero++;
            String legenda = legendaDe(p.text());
            p.html(MontagemDoHtml.rotulo(numero, legenda));
            ordem.add(new Saidas.ImagemNumerada(id, numero, true, legenda));
        }
        List<ImagemDaSaida> fora = imagens.stream().filter(i -> !vistas.contains(i.id()))
                .sorted(Comparator.comparingInt(ImagemDaSaida::numero)).toList();
        for (ImagemDaSaida imagem : fora) {
            ordem.add(new Saidas.ImagemNumerada(imagem.id(), ++numero, false, imagem.legenda()));
        }
        return new Renumerado(corpo(doc), ordem);
    }

    /**
     * As imagens na ordem pedida, nos lugares que as imagens já ocupam no texto (cada lugar recebe a imagem da vez,
     * com a legenda dela). Lugar que sobra sai; imagem que não cabe nos lugares vai para o fim do texto.
     */
    static String permutar(String html, List<UUID> incluidas, List<ImagemDaSaida> imagens) {
        Map<UUID, ImagemDaSaida> porId = imagens.stream()
                .collect(Collectors.toMap(ImagemDaSaida::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Document doc = fragmento(html);
        List<Element> lugares = new ArrayList<>(doc.select("p[data-imagem]"));
        for (int i = 0; i < Math.max(lugares.size(), incluidas.size()); i++) {
            if (i >= incluidas.size()) {
                lugares.get(i).remove();
                continue;
            }
            ImagemDaSaida imagem = porId.get(incluidas.get(i));
            Element p = i < lugares.size() ? lugares.get(i) : doc.body().appendElement("p");
            p.attr("data-imagem", imagem.id().toString()).html(MontagemDoHtml.rotulo(i + 1, imagem.legenda()));
        }
        return corpo(doc);
    }

    /**
     * O HTML para colar no sistema de chamados: sem os marcadores da tela; com {@code dataUri}, cada imagem entra
     * embutida logo depois do "Imagem N" dela.
     *
     * @param dataUri o endereço {@code data:} de cada imagem, ou vazio se ela não pode ir (null = só o texto)
     */
    static String paraCopiar(String html, Function<UUID, Optional<String>> dataUri) {
        Document doc = fragmento(html);
        if (dataUri != null) {
            for (Element p : doc.select("p[data-imagem]")) {
                UUID id = uuid(p.attr("data-imagem"));
                Optional<String> endereco = id == null ? Optional.empty() : dataUri.apply(id);
                if (endereco.isPresent()) {
                    Element img = new Element("img").attr("src", endereco.get()).attr("alt", rotuloDe(p.text()));
                    p.after(new Element("p").appendChild(img));
                }
            }
        }
        doc.select("[data-fontes]").removeAttr("data-fontes");
        doc.select("[data-imagem]").removeAttr("data-imagem");
        return corpo(doc);
    }

    /** O texto sem formatação (para quem cola num campo de texto simples): parágrafos e passos numerados. */
    static String textoSimples(String html) {
        Document doc = fragmento(paraCopiar(html, null));
        for (Element br : doc.select("br")) {
            br.replaceWith(new TextNode("\n"));
        }
        StringBuilder texto = new StringBuilder();
        for (Element e : doc.body().children()) {
            switch (e.tagName()) {
                case "ol", "ul" -> {
                    int n = 0;
                    for (Element li : e.children()) {
                        n++;
                        texto.append("ol".equals(e.tagName()) ? n + ". " : "- ").append(textoDoItem(li)).append('\n');
                    }
                    texto.append('\n');
                }
                default -> texto.append(e.wholeText().strip()).append("\n\n");
            }
        }
        return texto.toString().strip();
    }

    private static String textoDoItem(Element li) {
        StringBuilder t = new StringBuilder();
        for (Node filho : li.childNodes()) {
            if (filho instanceof Element el && "p".equals(el.tagName())) {
                t.append("\n   ").append(el.wholeText().strip());
            } else if (filho instanceof Element el) {
                t.append(el.wholeText());
            } else if (filho instanceof TextNode tn) {
                t.append(tn.getWholeText());
            }
        }
        return t.toString().strip();
    }

    /** "Imagem 2: tela de pedidos" → "tela de pedidos"; só o rótulo → null. */
    static String legendaDe(String texto) {
        String t = texto == null ? "" : texto.strip();
        Matcher m = ROTULO.matcher(t);
        String resto = m.lookingAt() ? t.substring(m.end()).strip() : t;
        if (resto.isEmpty()) {
            return null;
        }
        return resto.length() <= LIMITE_DA_LEGENDA ? resto : resto.substring(0, LIMITE_DA_LEGENDA).strip();
    }

    private static String rotuloDe(String texto) {
        Matcher m = Pattern.compile("(?i)imagem\\s*\\d+").matcher(texto == null ? "" : texto);
        return m.find() ? m.group() : "Imagem";
    }

    static UUID uuid(String texto) {
        try {
            return texto == null || texto.isBlank() ? null : UUID.fromString(texto.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Document fragmento(String html) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        doc.outputSettings().prettyPrint(false);
        return doc;
    }

    private static String corpo(Document doc) {
        return doc.body().html();
    }
}
