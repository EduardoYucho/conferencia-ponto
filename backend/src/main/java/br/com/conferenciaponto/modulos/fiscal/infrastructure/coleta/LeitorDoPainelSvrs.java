package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Alvo;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Origem;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a página "Disponibilidade" de um portal da SVRS (NF-e, NFC-e, CT-e, MDF-e). Cada serviço aparece numa linha
 * de tabela como "WS Autorizacao (URL) 16:51:34 - WS com status normal (CStat: 225)"; as tabelas da NF-e e da NFC-e
 * vêm com um título ("SEFAZ-RS", "SEFAZ Virtual do RS"), as do CT-e e do MDF-e não (são da SVRS). A leitura é pelo
 * texto das linhas, não pela posição das colunas, para sobreviver a mudanças pequenas de layout; uma página sem
 * nenhuma linha reconhecida é "fonte com problema", nunca "tudo normal".
 */
public final class LeitorDoPainelSvrs {

    /** "16:51:34 - WS com status normal (CStat: 225)" */
    private static final Pattern VERIFICACAO =
            Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})\\s*-\\s*(.+?)\\s*\\(\\s*CStat\\s*:?\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE);
    /** Sem o código: "16:51:34 - WS fora do ar". */
    private static final Pattern VERIFICACAO_SEM_CSTAT = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})\\s*-\\s*(.+)$");
    private static final Pattern TITULO = Pattern.compile("(?i).*\\b(sefaz|sefa)\\b.*");
    private static final Pattern AVISO = Pattern.compile(
            "(?i)(n[ãa]o h[áa] manuten[çc][ãa]o prevista[^.]*\\.?"
                    + "|manuten[çc][ãa]o (?:programada|prevista)\\s+(?:para|em|no|na|dia|de)\\b[^.]{0,250}\\.?"
                    + "|(?:haver[áa]|ocorrer[áa]|ser[áa] realizada)[^.]{0,60}manuten[çc][ãa]o[^.]{0,250}\\.?)");
    private static final Set<String> TAGS_DE_TITULO = Set.of("h1", "h2", "h3", "h4", "h5", "h6", "caption", "legend",
            "strong", "b", "span", "div", "p", "th", "label", "a", "td");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param erro vazio quando a página trouxe ao menos um serviço
     */
    public record Lido(List<Leitura> leituras, String aviso, String erro) {
    }

    private record Linha(int ordem, String texto) {
    }

    private record Titulo(int ordem, String autorizador) {
    }

    public Lido ler(String html, Documento documento, Instant agora, ZoneId fuso, Duration verificacaoAntiga) {
        return ler(Jsoup.parse(html == null ? "" : html), documento, agora, fuso, verificacaoAntiga);
    }

    /** A página como veio da rede; sem {@code charset} no cabeçalho, vale o da própria página (ou UTF-8). */
    public Lido ler(byte[] corpo, String charset, Documento documento, Instant agora, ZoneId fuso,
                    Duration verificacaoAntiga) {
        Document pagina;
        try {
            pagina = Jsoup.parse(new ByteArrayInputStream(corpo), charset, "");
        } catch (IOException | IllegalArgumentException e) {
            pagina = Jsoup.parse(new String(corpo, StandardCharsets.UTF_8));
        }
        return ler(pagina, documento, agora, fuso, verificacaoAntiga);
    }

    private Lido ler(Document pagina, Documento documento, Instant agora, ZoneId fuso, Duration verificacaoAntiga) {
        List<Linha> linhas = new ArrayList<>();
        List<Titulo> titulos = new ArrayList<>();
        int ordem = 0;
        for (Element e : pagina.body().getAllElements()) {
            ordem++;
            if (e.normalName().equals("tr")) {
                linhas.add(new Linha(ordem, e.text()));
            } else if (TAGS_DE_TITULO.contains(e.normalName())) {
                String proprio = e.ownText().strip();
                if (!proprio.isEmpty() && proprio.length() <= 60 && TITULO.matcher(proprio).matches()) {
                    titulos.add(new Titulo(ordem, autorizadorDoTitulo(proprio)));
                }
            }
        }
        // o título pode vir antes de cada tabela ou depois dela: se a primeira linha de serviço vem antes de qualquer
        // título, a página põe os títulos depois, e cada linha fica com o próximo título
        if (documento == Documento.CTE || documento == Documento.MDFE) {
            titulos.clear(); // um painel só da SVRS: um "SEFAZ" no menu da página não é grupo
        }
        List<Linha> deServico = linhas.stream().filter(l -> VERIFICACAO_SEM_CSTAT.matcher(l.texto()).find()).toList();
        boolean tituloDepois = !deServico.isEmpty() && !titulos.isEmpty() && titulos.get(0).ordem() > deServico.get(0).ordem();

        List<Leitura> leituras = new ArrayList<>();
        Map<String, Integer> repetidos = new HashMap<>();
        for (Linha linha : deServico) {
            String texto = linha.texto();
            Matcher m = VERIFICACAO.matcher(texto);
            boolean comCodigo = m.find();
            if (!comCodigo) {
                m = VERIFICACAO_SEM_CSTAT.matcher(texto);
                if (!m.find()) {
                    continue;
                }
            }
            String nomeLido = texto.substring(0, m.start()).strip();
            if (nomeLido.toUpperCase(Locale.ROOT).contains("HMLE") || nomeLido.isEmpty()) {
                continue; // a verificação do ambiente de homologação
            }
            String autorizador = autorizadorDaLinha(linha, titulos, tituloDepois);
            String servico = servico(nomeLido);
            String chave = autorizador + "|" + servico;
            int vezes = repetidos.merge(chave, 1, Integer::sum);
            if (vezes > 1) {
                servico = servico + " (" + vezes + ")";
            }
            String situacaoLida = m.group(2).strip();
            Integer cstat = comCodigo ? Integer.valueOf(m.group(3)) : null;
            Instant verificado = verificadoEm(m.group(1), agora, fuso);
            Alvo alvo = new Alvo(documento, autorizador, servico);
            Duration idade = Duration.between(verificado, agora);
            if (idade.compareTo(verificacaoAntiga) > 0) {
                leituras.add(new Leitura(alvo, Origem.PAINEL_SVRS, Situacao.SEM_INFORMACAO,
                        "O portal não verifica este serviço desde %s (há %d min)."
                                .formatted(HORA.format(verificado.atZone(fuso)), idade.toMinutes()),
                        cstat, null, verificado));
            } else {
                leituras.add(new Leitura(alvo, Origem.PAINEL_SVRS, situacao(situacaoLida), situacaoLida, cstat, null,
                        verificado));
            }
        }
        String erro = leituras.isEmpty() && repetidos.isEmpty()
                ? "O painel não trouxe nenhum serviço (o formato da página pode ter mudado)." : null;
        return new Lido(leituras, aviso(pagina.body().text()), erro);
    }

    /** "SEFAZ Virtual do RS" → SVRS; "SEFAZ-RS" → RS. */
    static String autorizadorDoTitulo(String titulo) {
        String t = semAcento(titulo);
        if (t.contains("virtual")) {
            return t.contains("nacional") || t.contains(" an") ? "SVAN" : "SVRS";
        }
        if (t.contains("rs") || t.contains("rio grande do sul")) {
            return "RS";
        }
        return "SVRS";
    }

    private static String autorizadorDaLinha(Linha linha, List<Titulo> titulos, boolean tituloDepois) {
        String escolhido = null;
        if (tituloDepois) {
            for (Titulo t : titulos) {
                if (t.ordem() > linha.ordem()) {
                    return t.autorizador();
                }
            }
            return titulos.get(titulos.size() - 1).autorizador();
        }
        for (Titulo t : titulos) {
            if (t.ordem() < linha.ordem()) {
                escolhido = t.autorizador();
            }
        }
        return escolhido != null ? escolhido : "SVRS"; // o CT-e e o MDF-e não têm título: são da SVRS
    }

    /** Nome lido (sem acento, sem espaço, sem o prefixo do documento) → nome na tela; o mais específico primeiro. */
    private static final List<Map.Entry<String, String>> SERVICOS = List.of(
            Map.entry("recepcaoevento", "Eventos"),
            Map.entry("evento", "Eventos"),
            Map.entry("recepcaoos", "Autorização CT-e OS"),
            Map.entry("recepcaogtve", "GTV-e"),
            Map.entry("recepcaosimp", "Autorização CT-e simplificado"),
            Map.entry("retrecepcao", "Retorno da autorização"),
            Map.entry("retautorizacao", "Retorno da autorização"),
            Map.entry("retornoautorizacao", "Retorno da autorização"),
            Map.entry("consultarecibo", "Retorno da autorização"),
            Map.entry("recepcao", "Autorização"),
            Map.entry("autorizacao", "Autorização"),
            Map.entry("consultacadastro", "Consulta cadastro"),
            Map.entry("cadconsulta", "Consulta cadastro"),
            Map.entry("consultanaoenc", "Consulta não encerrados"),
            Map.entry("consnaoenc", "Consulta não encerrados"),
            Map.entry("consultaprotocolo", "Consulta situação"),
            Map.entry("consultasituacao", "Consulta situação"),
            Map.entry("consulta", "Consulta situação"),
            Map.entry("inutilizacao", "Inutilização"),
            Map.entry("statusservico", "Status do serviço"),
            Map.entry("status", "Status do serviço"),
            Map.entry("distribuicao", "Distribuição DF-e"),
            Map.entry("qrcode", "QR Code"));

    /** "WS Autorizacao (URL)" → "Autorização"; um nome desconhecido aparece como veio. */
    static String servico(String lido) {
        String limpo = lido.replaceAll("(?i)\\(url\\)", " ").replaceAll("\\d{2}/\\d{2}/\\d{4}", " ")
                .replaceAll("(?i)^\\s*ws\\s+", " ")
                .replaceAll("\\s+", " ").strip();
        String chave = semAcento(limpo).replaceAll("[^a-z0-9]", "").replaceFirst("^(nfce|nfe|cte|mdfe)", "")
                .replaceFirst("v?\\d+$", "");
        for (Map.Entry<String, String> s : SERVICOS) {
            if (chave.startsWith(s.getKey())) {
                return s.getValue();
            }
        }
        return limpo.isEmpty() ? "Serviço" : Character.toUpperCase(limpo.charAt(0)) + limpo.substring(1);
    }

    /** O que o painel diz → a situação. Um texto que não se conhece conta como instável (pede atenção). */
    static Situacao situacao(String texto) {
        String t = semAcento(texto);
        if (t.contains("normal")) {
            return Situacao.NORMAL;
        }
        for (String fora : List.of("fora", "indispon", "erro", "falha", "paralis", "timeout", "tempo esgot", "nao respond",
                "sem resposta", "inoperan")) {
            if (t.contains(fora)) {
                return Situacao.FORA;
            }
        }
        return Situacao.INSTAVEL;
    }

    private static Instant verificadoEm(String hora, Instant agora, ZoneId fuso) {
        ZonedDateTime local = agora.atZone(fuso);
        LocalTime lida = LocalTime.parse(hora.length() == 7 ? "0" + hora : hora);
        ZonedDateTime verificado = ZonedDateTime.of(LocalDate.from(local), lida, fuso);
        if (verificado.isAfter(local.plusMinutes(10))) {
            verificado = verificado.minusDays(1); // verificado ontem, perto da meia-noite
        }
        return verificado.toInstant();
    }

    private static String aviso(String texto) {
        Matcher m = AVISO.matcher(texto);
        return m.find() ? m.group(1).strip() : null;
    }

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
}
