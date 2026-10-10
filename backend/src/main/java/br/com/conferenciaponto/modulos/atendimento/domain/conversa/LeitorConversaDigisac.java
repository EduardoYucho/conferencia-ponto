package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a conversa de um PDF exportado pelo Digisac (sem IA): cabeçalho, mensagens com lado, remetente e horário,
 * eventos do sistema e anexos (links), cada anexo ligado à mensagem em que foi enviado.
 *
 * <p>O layout, conferido em PDFs reais (A4, gerados pelo Chrome sem interface):
 * <ul>
 *   <li>título do documento {@code Digisac - Ticket: <número>};</li>
 *   <li>cabeçalho com o contato (e o telefone, que não é guardado), "Chamado número:", "Início do chamado:",
 *       "Término do chamado:", "Assunto:" e "Resumo:", até o título "Mensagens do chamado";</li>
 *   <li>mensagens em balões: as do cliente à esquerda, as do atendente e do bot à direita. A primeira linha do
 *       balão tem o remetente e o horário {@code HH:MM} (em letra menor); as seguintes, o texto;</li>
 *   <li>centralizados: os separadores de data ({@code dd/mm/aaaa}) e os eventos ("Início do chamado - ...",
 *       "Chamado transferido por ...", "Fim do chamado - ...");</li>
 *   <li>anexos: links assinados (com {@code response-content-disposition} e validade {@code X-Amz-Date} +
 *       {@code X-Amz-Expires}) sobre a imagem ou sobre um rótulo como "Áudio";</li>
 *   <li>rodapé e avisos do próprio Digisac em letra miúda, às vezes por cima da conversa: ficam de fora.</li>
 * </ul>
 *
 * <p>Quando o Digisac mudar o layout, a {@link #VERSAO} sobe junto com a correção (fica gravada em cada atendimento).
 */
public final class LeitorConversaDigisac {

    /** Versão do leitor, gravada no atendimento. */
    public static final int VERSAO = 1;

    /** Trechos a até esta distância vertical estão na mesma linha (o horário, menor, fica na mesma linha de base). */
    private static final float MESMA_LINHA = 2.5f;
    /** Entre as linhas de um balão a distância é ~12,5; entre balões, ~31. */
    private static final float NOVO_BALAO = 20f;
    /** Um evento comprido quebra em linhas centralizadas seguidas. */
    private static final float CONTINUA_EVENTO = 16f;
    /** Distância máxima entre o centro do trecho e o meio da página para ele ser centralizado. */
    private static final float CENTRALIZADO = 25f;
    /** Letra menor que 60% da letra da conversa é rodapé ou aviso do Digisac. */
    private static final float LETRA_MIUDA = 0.6f;
    private static final float FOLGA_DO_LINK = 1.5f;

    /** O horário do balão, às vezes depois de uma marca em letra menor (ex.: "editada 10:30"). */
    private static final Pattern HORA_NO_FIM = Pattern.compile("(?:(.{1,30}?)\\s+)?(\\d{1,2}):(\\d{2})");
    private static final Pattern TERMINA_COM_HORA = Pattern.compile("(.{1,120}?)\\s+(\\d{1,2}):(\\d{2})");
    private static final Pattern DATA = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})");
    private static final Pattern DATA_HORA =
            Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern TELEFONE = Pattern.compile("\\s*\\(\\s*\\+?\\d[\\d\\s\\-.]{6,}\\d\\s*\\)");
    private static final Pattern TICKET = Pattern.compile("Ticket:\\s*(\\S+)");
    private static final Pattern ROTULO_DO_CABECALHO =
            Pattern.compile("^(conexao|chamado numero|inicio do chamado|termino do chamado|assunto|resumo)\\b");
    private static final Pattern NOME_UTF8 = Pattern.compile("(?i)filename\\*\\s*=\\s*[\\w-]*''([^;]+)");
    private static final Pattern NOME_ENTRE_ASPAS = Pattern.compile("(?i)filename\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern NOME_SIMPLES = Pattern.compile("(?i)filename\\s*=\\s*([^;\"]+)");
    private static final Pattern EXTENSAO = Pattern.compile("[a-z0-9]{1,10}");
    private static final DateTimeFormatter DATA_AMZ =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Comparator<Trecho> ORDEM_DE_LEITURA =
            Comparator.comparingInt(Trecho::pagina).thenComparingDouble(Trecho::y).thenComparingDouble(Trecho::x0);

    private final ZoneId fuso;

    /** @param fuso fuso dos horários impressos no PDF */
    public LeitorConversaDigisac(ZoneId fuso) {
        this.fuso = fuso;
    }

    /**
     * @throws LeituraDoPdfException PDF_NAO_E_DIGISAC, PDF_LAYOUT_DESCONHECIDO ou PDF_SEM_MENSAGENS
     */
    public ConversaLida ler(PdfPosicionado pdf) {
        String titulo = pdf.titulo() == null ? "" : limpar(pdf.titulo());
        if (!titulo.startsWith("Digisac - Ticket")) {
            throw LeituraDoPdfException.naoEDigisac();
        }
        float largura = pdf.largura();
        List<Trecho> trechos = legiveis(pdf.trechos());
        Trecho ancora = trechos.stream().filter(t -> simples(t.texto()).equals("mensagens do chamado")).findFirst()
                .orElseThrow(LeituraDoPdfException::layoutDesconhecido);
        Cabecalho cabecalho = lerCabecalho(trechos.stream().filter(t -> antes(t, ancora)).toList(), titulo, largura);

        Map<String, LinkDoPdf> porUrl = new LinkedHashMap<>();
        int ignorados = 0;
        for (LinkDoPdf link : pdf.links()) {
            if (link.url() == null || !ehAnexo(parametros(link.url()))) {
                ignorados++;
            } else {
                porUrl.merge(link.url(), link, LeitorConversaDigisac::unir);
            }
        }
        List<LinkDoPdf> links = porUrl.values().stream()
                .sorted(Comparator.comparingInt(LinkDoPdf::pagina).thenComparingDouble(LinkDoPdf::y0)
                        .thenComparingDouble(LinkDoPdf::x0))
                .toList();

        // o rótulo do link (ex.: "Áudio") não é texto da mensagem: o anexo entra nela como anexo
        List<Trecho> corpo = trechos.stream()
                .filter(t -> depois(t, ancora))
                .filter(t -> links.stream().noneMatch(l -> l.contem(t, FOLGA_DO_LINK)))
                .toList();
        Montagem montagem = new Montagem(largura, cabecalho.inicio() == null ? null
                : LocalDate.ofInstant(cabecalho.inicio(), fuso), cabecalho.contato());
        for (List<Trecho> linha : linhas(corpo)) {
            montagem.ler(linha);
        }
        List<Parcial> parciais = montagem.itens;
        long mensagens = parciais.stream().filter(p -> p.tipo == TipoDeItem.MENSAGEM).count();
        if (mensagens == 0) {
            throw LeituraDoPdfException.semMensagens();
        }
        if (parciais.stream().noneMatch(p -> p.tipo == TipoDeItem.MENSAGEM && p.remetente != null)) {
            throw LeituraDoPdfException.layoutDesconhecido();
        }

        for (int i = 0; i < parciais.size(); i++) {
            parciais.get(i).ordem = i + 1;
        }
        List<AnexoLido> anexos = new ArrayList<>();
        for (LinkDoPdf link : links) {
            anexos.add(anexo(anexos.size() + 1, link, parciais, largura));
        }
        List<ItemDaConversa> itens = parciais.stream().map(Parcial::item).toList();
        return new ConversaLida(cabecalho, itens, anexos, ignorados, montagem.semCabecalho);
    }

    // ------------------------------------------------------------------------------------------ cabeçalho

    private Cabecalho lerCabecalho(List<Trecho> trechos, String titulo, float largura) {
        String chamado = null;
        Instant inicio = null;
        Instant fim = null;
        int assunto = -1;
        int resumo = -1;
        for (int i = 0; i < trechos.size(); i++) {
            String texto = limpar(trechos.get(i).texto());
            String chave = simples(texto);
            if (chave.startsWith("chamado numero:")) {
                chamado = depoisDosDoisPontos(texto);
            } else if (chave.startsWith("inicio do chamado:")) {
                inicio = dataHora(depoisDosDoisPontos(texto));
            } else if (chave.startsWith("termino do chamado:")) {
                fim = dataHora(depoisDosDoisPontos(texto));
            } else if (chave.startsWith("assunto:") && assunto < 0) {
                assunto = i;
            } else if (chave.startsWith("resumo:") && resumo < 0) {
                resumo = i;
            }
        }
        if (chamado == null || chamado.isBlank()) {
            Matcher ticket = TICKET.matcher(titulo);
            chamado = ticket.find() ? ticket.group(1) : null;
        }
        if (chamado == null || chamado.isBlank() || chamado.length() > 30) {
            throw LeituraDoPdfException.layoutDesconhecido();
        }
        if (inicio != null && fim != null && fim.isBefore(inicio)) {
            fim = null;
        }
        String contato = null;
        int ateOAssunto = assunto >= 0 ? assunto : trechos.size();
        for (int i = 0; i < ateOAssunto && contato == null; i++) {
            Trecho t = trechos.get(i);
            String texto = limpar(t.texto());
            if (t.x0() < largura / 2 && texto.codePoints().anyMatch(Character::isLetter)
                    && !ROTULO_DO_CABECALHO.matcher(simples(texto)).find()) {
                contato = semTelefone(texto);
            }
        }
        String textoDoAssunto = assunto < 0 ? null : valorEntre(trechos, assunto, resumo > assunto ? resumo : trechos.size());
        String textoDoResumo = resumo < 0 ? null : valorEntre(trechos, resumo, trechos.size());
        return new Cabecalho(chamado.strip(), cortar(contato, 150), inicio, fim, textoDoAssunto, textoDoResumo);
    }

    /** O valor de um rótulo do cabeçalho: o resto do trecho do rótulo e as linhas até o próximo rótulo. */
    private static String valorEntre(List<Trecho> trechos, int rotulo, int proximo) {
        StringBuilder valor = new StringBuilder(depoisDosDoisPontos(limpar(trechos.get(rotulo).texto())));
        float y = trechos.get(rotulo).y();
        for (int i = rotulo + 1; i < proximo; i++) {
            Trecho t = trechos.get(i);
            if (!valor.isEmpty()) {
                valor.append(Math.abs(t.y() - y) <= MESMA_LINHA ? " " : "\n");
            }
            valor.append(limpar(t.texto()));
            y = t.y();
        }
        String texto = valor.toString().strip();
        return texto.isEmpty() || texto.equals("-") ? null : cortar(texto, 4000);
    }

    private Instant dataHora(String texto) {
        Matcher m = DATA_HORA.matcher(texto);
        return m.find() ? instante(m) : null;
    }

    private Instant instante(Matcher dataHora) {
        try {
            LocalDateTime local = LocalDateTime.of(Integer.parseInt(dataHora.group(3)), Integer.parseInt(dataHora.group(2)),
                    Integer.parseInt(dataHora.group(1)), Integer.parseInt(dataHora.group(4)), Integer.parseInt(dataHora.group(5)),
                    dataHora.group(6) == null ? 0 : Integer.parseInt(dataHora.group(6)));
            return local.atZone(fuso).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ corpo

    /** Item em montagem (mensagem ou evento), com as linhas de texto e a posição do começo. */
    private static final class Parcial {
        final TipoDeItem tipo;
        final Lado lado;
        final String remetente;
        final Instant momento;
        final int pagina;
        final float y;
        final List<String> linhas = new ArrayList<>();
        final List<Integer> anexos = new ArrayList<>();
        int ordem;

        Parcial(TipoDeItem tipo, Lado lado, String remetente, Instant momento, int pagina, float y) {
            this.tipo = tipo;
            this.lado = lado;
            this.remetente = remetente;
            this.momento = momento;
            this.pagina = pagina;
            this.y = y;
        }

        ItemDaConversa item() {
            String texto = Normalizer.normalize(String.join("\n", linhas), Normalizer.Form.NFC);
            return new ItemDaConversa(ordem, tipo, lado, remetente, momento, texto, anexos, pagina);
        }
    }

    /** Percorre as linhas do corpo em ordem e monta as mensagens e os eventos. */
    private final class Montagem {
        final List<Parcial> itens = new ArrayList<>();
        final float largura;
        LocalDate data;
        Parcial mensagem;
        Parcial evento;
        int pagina = -1;
        float yAnterior;
        int semCabecalho;
        /** Remetentes já vistos (e o contato do cabeçalho): só eles valem quando o horário vem colado ao nome. */
        final Set<String> remetentes = new HashSet<>();

        Montagem(float largura, LocalDate dataDoInicio, String contato) {
            this.largura = largura;
            this.data = dataDoInicio;
            if (contato != null) {
                remetentes.add(contato);
            }
        }

        void ler(List<Trecho> linha) {
            Trecho primeiro = linha.get(0);
            boolean novoBloco = primeiro.pagina() != pagina || primeiro.y() - yAnterior > NOVO_BALAO;
            boolean continuaEvento = evento != null && primeiro.pagina() == pagina
                    && primeiro.y() - yAnterior <= CONTINUA_EVENTO;
            pagina = primeiro.pagina();
            yAnterior = primeiro.y();

            if (linha.size() == 1 && centralizado(primeiro)) {
                String texto = limpar(primeiro.texto());
                Matcher separador = DATA.matcher(texto);
                mensagem = null;
                if (separador.matches()) {
                    evento = null;
                    data = dataDoSeparador(separador);
                } else if (continuaEvento) {
                    evento.linhas.add(texto);
                } else {
                    evento = new Parcial(TipoDeItem.EVENTO, null, null, dataHoraDoEvento(texto), pagina, primeiro.y());
                    evento.linhas.add(texto);
                    itens.add(evento);
                }
                return;
            }
            evento = null;

            Trecho ultimo = linha.get(linha.size() - 1);
            String remetente = null;
            LocalTime hora = null;
            List<Trecho> resto = linha;
            Matcher noFim = HORA_NO_FIM.matcher(limpar(ultimo.texto()));
            boolean letraMenor = ultimo.tamanho() < primeiro.tamanho() - 0.3f;
            if (linha.size() >= 2 && noFim.matches() && (letraMenor || noFim.group(1) == null
                    && remetentes.contains(semTelefone(juntar(linha.subList(0, linha.size() - 1)))))) {
                // cabeçalho do balão: o remetente e, em letra menor, o horário (às vezes com uma marca antes, como
                // "editada"); com letra do mesmo tamanho, só um remetente que já apareceu
                hora = hora(noFim.group(2), noFim.group(3));
                resto = linha.subList(0, linha.size() - 1);
                remetente = juntar(resto);
                if (hora != null && semTelefone(remetente) != null) {
                    remetentes.add(semTelefone(remetente));
                }
            } else if (novoBloco) {
                // horário no mesmo trecho do nome (letra do mesmo tamanho): só se o nome já apareceu, para a
                // legenda de uma imagem terminada em horário ("o erro foi às 10:30") não virar mensagem nova
                Matcher comHora = TERMINA_COM_HORA.matcher(juntar(linha));
                if (comHora.matches() && remetentes.contains(semTelefone(comHora.group(1)))) {
                    hora = hora(comHora.group(2), comHora.group(3));
                    remetente = hora == null ? null : comHora.group(1);
                }
            }
            Lado lado = primeiro.x0() < largura / 2 ? Lado.CLIENTE : Lado.ATENDENTE;
            if (hora != null) {
                Instant momento = data == null ? null : LocalDateTime.of(data, hora).atZone(fuso).toInstant();
                mensagem = new Parcial(TipoDeItem.MENSAGEM, lado, cortar(semTelefone(remetente), 150), momento,
                        pagina, primeiro.y());
                itens.add(mensagem);
                return;
            }
            String texto = juntar(linha);
            if (mensagem == null || mensagem.lado != lado) {
                semCabecalho++;
                mensagem = new Parcial(TipoDeItem.MENSAGEM, lado, null, null, pagina, primeiro.y());
                itens.add(mensagem);
            }
            mensagem.linhas.add(texto);
        }

        /** Um trecho sozinho na linha, que passa pelo meio da página e tem o centro nele: evento ou data. */
        boolean centralizado(Trecho t) {
            float meio = largura / 2;
            return t.x0() < meio && t.x1() > meio && Math.abs(t.centro() - meio) <= CENTRALIZADO;
        }

        LocalDate dataDoSeparador(Matcher m) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (DateTimeException e) {
                return data;
            }
        }

        Instant dataHoraDoEvento(String texto) {
            Matcher m = DATA_HORA.matcher(texto);
            Instant ultima = null;
            while (m.find()) {
                ultima = instante(m);
            }
            return ultima;
        }
    }

    private static LocalTime hora(String horas, String minutos) {
        try {
            return LocalTime.of(Integer.parseInt(horas), Integer.parseInt(minutos));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Agrupa os trechos (já em ordem de leitura) em linhas: mesma página e mesma altura, da esquerda para a direita. */
    private static List<List<Trecho>> linhas(List<Trecho> trechos) {
        List<List<Trecho>> linhas = new ArrayList<>();
        List<Trecho> atual = new ArrayList<>();
        for (Trecho t : trechos) {
            if (!atual.isEmpty() && (t.pagina() != atual.get(0).pagina() || t.y() - atual.get(0).y() > MESMA_LINHA)) {
                linhas.add(ordenarPorX(atual));
                atual = new ArrayList<>();
            }
            atual.add(t);
        }
        if (!atual.isEmpty()) {
            linhas.add(ordenarPorX(atual));
        }
        return linhas;
    }

    private static List<Trecho> ordenarPorX(List<Trecho> linha) {
        List<Trecho> ordenada = new ArrayList<>(linha);
        ordenada.sort(Comparator.comparingDouble(Trecho::x0));
        return List.copyOf(ordenada);
    }

    /** Tira a letra miúda (rodapé e avisos do Digisac) e os trechos vazios; devolve em ordem de leitura. */
    private static List<Trecho> legiveis(List<Trecho> trechos) {
        List<Trecho> ordenados = trechos.stream().filter(t -> !t.texto().isBlank()).sorted(ORDEM_DE_LEITURA).toList();
        float tamanhoDaConversa = tamanhoDaConversa(ordenados);
        return ordenados.stream().filter(t -> t.tamanho() >= tamanhoDaConversa * LETRA_MIUDA).toList();
    }

    /**
     * O tamanho da letra da conversa: o do remetente nas linhas de cabeçalho dos balões (remetente ... HH:MM). Se o
     * PDF não tiver nenhuma, o tamanho com mais letras. Contar só as letras não basta: numa conversa curta, o rodapé
     * de propaganda (repetido em toda página) pode ter mais letras do que as mensagens.
     */
    private static float tamanhoDaConversa(List<Trecho> ordenados) {
        Map<Integer, Integer> votos = new HashMap<>();
        for (List<Trecho> linha : linhas(ordenados)) {
            Trecho ultimo = linha.get(linha.size() - 1);
            if (linha.size() >= 2 && HORA_NO_FIM.matcher(limpar(ultimo.texto())).matches()) {
                votos.merge(Math.round(linha.get(0).tamanho() * 2), 1, Integer::sum); // o remetente
            }
        }
        if (votos.isEmpty()) {
            for (Trecho t : ordenados) {
                votos.merge(Math.round(t.tamanho() * 2), t.texto().length(), Integer::sum);
            }
        }
        return votos.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey() / 2f).orElse(0f);
    }

    private static boolean antes(Trecho t, Trecho ancora) {
        return t.pagina() < ancora.pagina() || t.pagina() == ancora.pagina() && t.y() < ancora.y() - MESMA_LINHA;
    }

    private static boolean depois(Trecho t, Trecho ancora) {
        return t.pagina() > ancora.pagina() || t.pagina() == ancora.pagina() && t.y() > ancora.y() + MESMA_LINHA;
    }

    // ------------------------------------------------------------------------------------------ anexos

    private AnexoLido anexo(int ordem, LinkDoPdf link, List<Parcial> itens, float largura) {
        Map<String, String> parametros = parametros(link.url());
        String nome = nomeDoAnexo(parametros.get("response-content-disposition"), link.url(), ordem);
        String extensao = extensao(nome);
        Lado lado = link.x0() < largura / 2 ? Lado.CLIENTE : Lado.ATENDENTE;
        Parcial mensagem = mensagemDoLink(link, lado, itens);
        if (mensagem != null) {
            mensagem.anexos.add(ordem);
        }
        return new AnexoLido(ordem, nome, extensao, CategoriaDoArquivo.porExtensao(extensao),
                CategoriaDoArquivo.tipoDeMidia(extensao), link.pagina(), link.y0(),
                mensagem == null ? null : mensagem.ordem, mensagem == null ? null : mensagem.momento, link.url(),
                validade(parametros));
    }

    /** A última mensagem que começa acima do link, de preferência do mesmo lado. */
    private static Parcial mensagemDoLink(LinkDoPdf link, Lado lado, List<Parcial> itens) {
        Parcial mesmoLado = null;
        Parcial qualquer = null;
        for (Parcial p : itens) {
            boolean acima = p.pagina < link.pagina() || p.pagina == link.pagina() && p.y <= link.y0() + 2;
            if (!acima) {
                break;
            }
            if (p.tipo == TipoDeItem.MENSAGEM) {
                qualquer = p;
                if (p.lado == lado) {
                    mesmoLado = p;
                }
            }
        }
        return mesmoLado != null ? mesmoLado : qualquer;
    }

    /** Anexo do Digisac: link assinado do armazenamento, com o nome do arquivo ou a assinatura. */
    private static boolean ehAnexo(Map<String, String> parametros) {
        return parametros.containsKey("response-content-disposition") || parametros.containsKey("x-amz-signature");
    }

    private static LinkDoPdf unir(LinkDoPdf a, LinkDoPdf b) {
        if (a.pagina() != b.pagina()) {
            return a;
        }
        return new LinkDoPdf(a.pagina(), Math.min(a.x0(), b.x0()), Math.max(a.x1(), b.x1()), Math.min(a.y0(), b.y0()),
                Math.max(a.y1(), b.y1()), a.url());
    }

    /** Parâmetros da URL, com o nome em minúsculas. */
    static Map<String, String> parametros(String url) {
        Map<String, String> parametros = new LinkedHashMap<>();
        int interrogacao = url.indexOf('?');
        if (interrogacao < 0) {
            return parametros;
        }
        String consulta = url.substring(interrogacao + 1);
        int cerquilha = consulta.indexOf('#');
        if (cerquilha >= 0) {
            consulta = consulta.substring(0, cerquilha);
        }
        for (String par : consulta.split("&")) {
            if (par.isEmpty()) {
                continue;
            }
            int igual = par.indexOf('=');
            String nome = igual < 0 ? par : par.substring(0, igual);
            String valor = igual < 0 ? "" : par.substring(igual + 1);
            parametros.putIfAbsent(decodificar(nome).toLowerCase(Locale.ROOT), decodificar(valor));
        }
        return parametros;
    }

    private static String decodificar(String texto) {
        try {
            return URLDecoder.decode(texto, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return texto;
        }
    }

    /** Nome original do arquivo: do content-disposition do link; sem ele, o fim do caminho da URL. */
    static String nomeDoAnexo(String disposicao, String url, int ordem) {
        String nome = null;
        if (disposicao != null) {
            Matcher m = NOME_UTF8.matcher(disposicao);
            if (m.find()) {
                nome = decodificar(m.group(1).replace("+", "%2B"));
            } else if ((m = NOME_ENTRE_ASPAS.matcher(disposicao)).find() || (m = NOME_SIMPLES.matcher(disposicao)).find()) {
                nome = m.group(1);
            }
        }
        if (nome == null || nome.isBlank()) {
            String caminho = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            nome = decodificar(caminho.substring(caminho.lastIndexOf('/') + 1).replace("+", "%2B"));
        }
        nome = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "").strip();
        return nome.isEmpty() ? "anexo-" + ordem : cortar(nome, 200);
    }

    static String extensao(String nome) {
        int ponto = nome.lastIndexOf('.');
        if (ponto <= 0 || ponto == nome.length() - 1) {
            return "";
        }
        String extensao = nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
        return EXTENSAO.matcher(extensao).matches() ? extensao : "";
    }

    private static Instant validade(Map<String, String> parametros) {
        String data = parametros.get("x-amz-date");
        String segundos = parametros.get("x-amz-expires");
        if (data == null || segundos == null) {
            return null;
        }
        try {
            return Instant.from(DATA_AMZ.parse(data)).plusSeconds(Long.parseLong(segundos.strip()));
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ texto

    private static String juntar(List<Trecho> trechos) {
        StringBuilder texto = new StringBuilder();
        for (Trecho t : trechos) {
            String parte = limpar(t.texto());
            if (!parte.isEmpty()) {
                if (!texto.isEmpty()) {
                    texto.append(' ');
                }
                texto.append(parte);
            }
        }
        return texto.toString();
    }

    private static String semTelefone(String texto) {
        if (texto == null) {
            return null;
        }
        String sem = TELEFONE.matcher(texto).replaceAll("").replaceAll("\\s+", " ").strip();
        return sem.isEmpty() ? null : sem;
    }

    private static String depoisDosDoisPontos(String texto) {
        int doisPontos = texto.indexOf(':');
        return doisPontos < 0 ? "" : texto.substring(doisPontos + 1).strip();
    }

    static String limpar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFC).replace('\u00a0', ' ').strip();
    }

    /** Para comparar rótulos: sem acento, minúsculo e com um espaço só. */
    static String simples(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    private static String cortar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
