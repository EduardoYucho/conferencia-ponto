package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conferência do texto que o Gemini redigiu, antes de virar HTML:
 *
 * <ul>
 *   <li>as fontes de cada trecho precisam existir na linha do tempo (as inventadas saem, com aviso);</li>
 *   <li>as imagens precisam estar entre as disponíveis (as de fora são descartadas: nada de tela inventada), e
 *       cada uma entra uma vez só, numerada na ordem em que aparece;</li>
 *   <li>trecho sem fonte fica marcado para a pessoa conferir;</li>
 *   <li>dados de acesso remoto que escaparem viram [omitido], com aviso;</li>
 *   <li>o assunto ganha o padrão {@code Digisac: <contato> : <assunto>} e não passa de 150 caracteres.</li>
 * </ul>
 */
public final class ConferenciaDoTexto {

    public static final int LIMITE_DO_ASSUNTO = 150;
    private static final int LIMITE_DA_LEGENDA = 200;
    private static final String OMITIDO = "[omitido]";
    private static final Pattern IDENTIFICADOR = Pattern.compile("^([MALPV])0*(\\d{1,5})$");

    /** Uma imagem que o texto pode usar (anexos e prints de imagem com arquivo no servidor). */
    public record ImagemDisponivel(String id, UUID arquivoId, String caminho, String legendaPadrao) {
    }

    /** O que veio do Gemini, já lido do JSON. */
    public record Bruto(String assunto, List<BlocoBruto> blocos, Map<String, String> base, String telaFuncionalidade) {
        public Bruto {
            blocos = blocos == null ? List.of() : blocos;
            base = base == null ? Map.of() : base;
        }
    }

    public record BlocoBruto(String tipo, String texto, List<String> fontes, List<ImagemBruta> imagens) {
        public BlocoBruto {
            fontes = fontes == null ? List.of() : fontes;
            imagens = imagens == null ? List.of() : imagens;
        }
    }

    public record ImagemBruta(String id, String legenda) {
    }

    /**
     * @param assuntoLongo o assunto do Gemini não coube nos 150 caracteres com o prefixo: quem chama pede um mais
     *                     curto ou corta ({@link #comAssunto})
     */
    public record Conferido(TextoRedigido texto, List<NovaImagem> imagens, String assuntoDoModelo, boolean assuntoLongo) {
    }

    private final Set<String> idsDaLinha;
    private final Map<String, ImagemDisponivel> catalogo;
    private final UnaryOperator<String> mascarar;
    private final Supplier<UUID> novoId;

    /**
     * @param mascarar troca dados de acesso remoto por [omitido] (o mesmo mascaramento da conversa)
     */
    public ConferenciaDoTexto(Set<String> idsDaLinha, List<ImagemDisponivel> catalogo, UnaryOperator<String> mascarar,
                              Supplier<UUID> novoId) {
        this.idsDaLinha = Set.copyOf(idsDaLinha);
        Map<String, ImagemDisponivel> porId = new LinkedHashMap<>();
        catalogo.forEach(i -> porId.put(i.id(), i));
        this.catalogo = porId;
        this.mascarar = mascarar;
        this.novoId = novoId;
    }

    public static String prefixoDoAssunto(String contato) {
        String nome = contato == null || contato.isBlank() ? "contato não identificado" : contato.strip().replaceAll("\\s+", " ");
        return "Digisac: " + nome + " : ";
    }

    /** Quantos caracteres sobram para o assunto depois do prefixo (pelo menos 20, mesmo com um nome muito longo). */
    public static int limiteDoAssunto(String contato) {
        return Math.max(20, LIMITE_DO_ASSUNTO - prefixoDoAssunto(contato).length());
    }

    /** Corta na última palavra inteira que cabe no limite (sem pontuação solta no fim). */
    public static String cortarNaUltimaPalavra(String texto, int limite) {
        String limpo = texto == null ? "" : texto.strip().replaceAll("\\s+", " ");
        if (limpo.length() <= limite) {
            return limpo;
        }
        String cortado = limpo.substring(0, limite);
        int espaco = cortado.lastIndexOf(' ');
        if (espaco > limite / 2) {
            cortado = cortado.substring(0, espaco);
        }
        return cortado.replaceAll("[\\s,;:.\\-–—]+$", "");
    }

    /** O assunto completo, no padrão, a partir do que veio do Gemini (sem cortar). */
    public static String assuntoCompleto(String contato, String assunto) {
        String limpo = assunto == null ? "" : assunto.strip().replaceAll("\\s+", " ");
        // o Gemini às vezes repete o prefixo pedido: não duplica
        String prefixo = prefixoDoAssunto(contato);
        if (limpo.regionMatches(true, 0, "Digisac:", 0, 8)) {
            int separador = limpo.indexOf(" : ", 8);
            limpo = separador > 0 ? limpo.substring(separador + 3).strip() : limpo.substring(8).strip();
        }
        if (limpo.isEmpty()) {
            limpo = "atendimento";
        }
        return prefixo + limpo;
    }

    public Conferido conferir(Bruto bruto, String contato) {
        List<String> avisos = new ArrayList<>();
        int fontesInventadas = 0;
        int imagensDeFora = 0;
        int imagensRepetidas = 0;
        int semFonte = 0;
        int[] mascarados = {0};

        List<BlocoDoTexto> blocos = new ArrayList<>();
        List<NovaImagem> imagens = new ArrayList<>();
        Set<String> usadas = new LinkedHashSet<>();
        for (BlocoBruto bloco : bruto.blocos()) {
            if (bloco == null) {
                continue;
            }
            Set<String> fontes = new LinkedHashSet<>();
            for (String fonte : bloco.fontes()) {
                String id = normalizar(fonte);
                if (id != null && idsDaLinha.contains(id)) {
                    fontes.add(id);
                } else if (fonte != null && !fonte.isBlank()) {
                    fontesInventadas++;
                }
            }
            List<BlocoDoTexto.ImagemCitada> citadas = new ArrayList<>();
            for (ImagemBruta imagem : bloco.imagens()) {
                if (imagem == null) {
                    continue;
                }
                String id = normalizar(imagem.id());
                ImagemDisponivel disponivel = id == null ? null : catalogo.get(id);
                if (disponivel == null) {
                    imagensDeFora++;
                    continue;
                }
                if (!usadas.add(id)) {
                    imagensRepetidas++;
                    continue;
                }
                String legenda = legenda(imagem.legenda(), disponivel.legendaPadrao(), mascarados);
                NovaImagem nova = new NovaImagem(novoId.get(), imagens.size() + 1, disponivel.arquivoId(),
                        disponivel.caminho(), legenda);
                imagens.add(nova);
                citadas.add(new BlocoDoTexto.ImagemCitada(id, nova.id(), nova.numero(), legenda));
                fontes.add(id); // a imagem também é fonte do trecho que ela ilustra
            }
            String texto = mascarado(bloco.texto() == null ? "" : bloco.texto().strip(), mascarados);
            if (texto.isEmpty() && citadas.isEmpty()) {
                continue;
            }
            if (fontes.isEmpty() && !texto.isEmpty()) {
                semFonte++;
            }
            blocos.add(new BlocoDoTexto(BlocoDoTexto.Tipo.doCodigo(bloco.tipo()), texto, List.copyOf(fontes), citadas));
        }

        Map<String, String> base = new LinkedHashMap<>();
        bruto.base().forEach((campo, valor) -> base.put(campo, mascarado(valor == null ? "" : valor.strip(), mascarados)));
        String tela = mascarado(Objects.requireNonNullElse(bruto.telaFuncionalidade(), "").strip(), mascarados);
        String assuntoDoModelo = mascarado(Objects.requireNonNullElse(bruto.assunto(), ""), mascarados);
        String assunto = assuntoCompleto(contato, assuntoDoModelo);

        if (blocos.isEmpty()) {
            avisos.add("O Gemini não devolveu nenhum trecho de texto.");
        }
        if (fontesInventadas > 0) {
            avisos.add(fontesInventadas + (fontesInventadas == 1 ? " fonte citada não existe" : " fontes citadas não existem")
                    + " no material e " + (fontesInventadas == 1 ? "foi tirada." : "foram tiradas."));
        }
        if (imagensDeFora > 0) {
            avisos.add(imagensDeFora + (imagensDeFora == 1 ? " imagem citada não está" : " imagens citadas não estão")
                    + " entre as do atendimento e " + (imagensDeFora == 1 ? "foi descartada." : "foram descartadas."));
        }
        if (imagensRepetidas > 0) {
            avisos.add(imagensRepetidas + (imagensRepetidas == 1 ? " imagem citada de novo ficou" : " imagens citadas de novo ficaram")
                    + " só na primeira vez em que aparecem.");
        }
        if (semFonte > 0) {
            avisos.add(semFonte + (semFonte == 1 ? " trecho veio sem fonte" : " trechos vieram sem fonte")
                    + " (destacados na prévia): confira antes de usar.");
        }
        if (mascarados[0] > 0) {
            avisos.add(mascarados[0] + (mascarados[0] == 1 ? " trecho com dado de acesso remoto foi trocado"
                    : " trechos com dados de acesso remoto foram trocados") + " por [omitido].");
        }
        TextoRedigido texto = new TextoRedigido(TextoRedigido.VERSAO, assunto, blocos, base, tela, avisos);
        return new Conferido(texto, imagens, assuntoDoModelo, assunto.length() > LIMITE_DO_ASSUNTO);
    }

    /**
     * O texto com o assunto final: o mais curto que o Gemini mandou (se veio) ou o original cortado na última palavra.
     */
    public static TextoRedigido comAssunto(TextoRedigido texto, String contato, String assuntoNovo) {
        List<String> avisos = new ArrayList<>(texto.avisos());
        String completo = assuntoCompleto(contato, assuntoNovo);
        if (completo.length() > LIMITE_DO_ASSUNTO) {
            String prefixo = prefixoDoAssunto(contato);
            String parte = cortarNaUltimaPalavra(completo.substring(prefixo.length()), limiteDoAssunto(contato));
            completo = (prefixo + parte);
            if (completo.length() > LIMITE_DO_ASSUNTO) {
                completo = cortarNaUltimaPalavra(completo, LIMITE_DO_ASSUNTO);
            }
            avisos.add("O assunto passou de " + LIMITE_DO_ASSUNTO + " caracteres e foi cortado na última palavra: confira.");
        }
        return texto.comAssunto(completo, avisos);
    }

    /** M12, A3...; aceita "m12", "A03", "[A3]" e "A3 (print.png)" (o Gemini às vezes enfeita). */
    static String normalizar(String fonte) {
        if (fonte == null) {
            return null;
        }
        String limpo = fonte.strip().toUpperCase(Locale.ROOT).replaceAll("^[\\[(]+", "");
        Matcher inicio = Pattern.compile("^([MALPV]\\d{1,5})").matcher(limpo);
        if (!inicio.find()) {
            return null;
        }
        Matcher m = IDENTIFICADOR.matcher(inicio.group(1));
        return m.matches() ? m.group(1) + Integer.parseInt(m.group(2)) : null;
    }

    private String legenda(String doModelo, String padrao, int[] mascarados) {
        String escolhida = doModelo != null && !doModelo.isBlank() ? doModelo : padrao;
        if (escolhida == null || escolhida.isBlank()) {
            return null;
        }
        String limpa = escolhida.strip().replaceAll("\\s+", " ").replaceFirst("(?i)^imagem\\s*\\d*\\s*[:\\-–]\\s*", "");
        return mascarado(cortarNaUltimaPalavra(limpa, LIMITE_DA_LEGENDA), mascarados);
    }

    private String mascarado(String texto, int[] mascarados) {
        if (texto == null || texto.isEmpty()) {
            return texto == null ? "" : texto;
        }
        String resultado = mascarar.apply(texto);
        int antes = contar(texto);
        int depois = contar(resultado);
        if (depois > antes) {
            mascarados[0] += depois - antes;
        }
        return resultado;
    }

    private static int contar(String texto) {
        int n = 0;
        for (int i = texto.indexOf(OMITIDO); i >= 0; i = texto.indexOf(OMITIDO, i + OMITIDO.length())) {
            n++;
        }
        return n;
    }
}
