package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Troca por {@value #OMITIDO}, antes de guardar e antes de qualquer envio ao Gemini, o que nunca pode sair do
 * atendimento: a chave temporária do bot e os dados de acesso remoto (IDs e senhas de UltraViewer, AnyDesk,
 * TeamViewer...).
 *
 * <p>As regras saíram da leitura (sem exibir os valores) de PDFs reais do Digisac:
 * <ul>
 *   <li><b>chave do bot</b>: o bot avisa a chave do mês numa frase como "...chave temporária para o mês de
 *       Outubro/2026 é: 1234567890 - Válida até o dia ...". Sai o valor depois do "é:";</li>
 *   <li><b>senha</b>: "Senha 1234", "senha: abc123", "a senha é 4321" (o valor precisa ter algum número, para não
 *       pegar "senha do sistema");</li>
 *   <li><b>ID com o nome da ferramenta</b>: "ID 123 456 789", "anydesk: 123456789", "ultra 12 345 678";</li>
 *   <li><b>ID solto</b>: uma mensagem que é só um número de 8 a 10 dígitos (com ou sem espaços), como o cliente
 *       manda o ID do UltraViewer;</li>
 *   <li><b>senha logo depois do ID</b>: a mensagem seguinte do mesmo lado que é uma palavra curta com número
 *       (ex.: "4821"), quando a anterior trouxe o ID sem a senha ou terminou em "senha".</li>
 * </ul>
 *
 * <p>Os valores trocados ficam só na memória ({@link Resultado#valores()}), para a conferência dos textos gerados;
 * nunca vão para o banco nem para o log.
 */
public final class Mascaramento {

    public static final String OMITIDO = "[omitido]";

    private static final String VALOR = "([\\p{L}\\p{N}]{4,})";
    private static final Pattern CHAVE_DO_MES =
            Pattern.compile("(?iu)(\\bm[eê]s\\s+de\\s+\\p{L}+\\s*/\\s*\\d{4}\\s+[ée]\\s*:?\\s*)" + VALOR);
    private static final Pattern CHAVE_TEMPORARIA =
            Pattern.compile("(?iu)(\\bchave\\s+tempor[aá]ria\\b[\\s\\S]{0,120}?\\s[ée]\\s*:?\\s*)" + VALOR);
    private static final Pattern SENHA =
            Pattern.compile("(?iu)(\\bsenha\\b\\s*(?:[:=\\-]\\s*|[ée]\\s*:?\\s*|eh\\s+)?)([^\\s,;]*\\d[^\\s,;]*)");
    private static final Pattern ID_COM_FERRAMENTA = Pattern.compile("(?iu)(\\b(?:ultra\\s*viewer|ultra|any\\s*desk"
            + "|team\\s*viewer|rust\\s*desk|id|c[oó]digo\\s+de\\s+acesso)\\b\\s*(?:[:=\\-]\\s*|[ée]\\s*:?\\s*)?)"
            + "(\\d[\\d .\\-]{5,}\\d)");
    private static final Pattern ID_SOLTO = Pattern.compile("\\d[\\d .\\-]*\\d");
    private static final Pattern PALAVRA_COM_NUMERO = Pattern.compile("(?=\\S*\\d)[\\p{L}\\p{N}@#$%&*!._\\-]{3,12}");
    private static final Pattern TERMINA_EM_SENHA = Pattern.compile("(?iu)\\bsenha\\s*[:?]?\\s*$");

    /**
     * @param valores o que foi trocado (só em memória; o toString não mostra)
     */
    public record Resultado(ConversaLida conversa, Omitidos omitidos, Set<String> valores) {

        public Resultado {
            valores = Set.copyOf(valores);
        }

        @Override
        public String toString() {
            return "Resultado[omitidos=" + omitidos + "]";
        }
    }

    /** O texto de uma mensagem depois das regras, com o que saiu. */
    record Texto(String texto, int chaveDoBot, int acessoRemoto, boolean idSemSenha, List<String> valores) {
    }

    public Resultado aplicar(ConversaLida conversa) {
        Set<String> valores = new LinkedHashSet<>();
        Omitidos omitidos = Omitidos.NENHUM;
        Map<Lado, Boolean> esperandoSenha = new EnumMap<>(Lado.class);
        List<ItemDaConversa> itens = new ArrayList<>();
        for (ItemDaConversa item : conversa.itens()) {
            if (!item.mensagem()) {
                itens.add(item);
                continue;
            }
            Texto texto = mascarar(item.texto(), esperandoSenha.getOrDefault(item.lado(), false));
            esperandoSenha.put(item.lado(), texto.idSemSenha() || TERMINA_EM_SENHA.matcher(item.texto()).find());
            valores.addAll(texto.valores());
            omitidos = omitidos.mais(new Omitidos(texto.chaveDoBot(), texto.acessoRemoto()));
            itens.add(item.comTexto(texto.texto()));
        }
        Cabecalho cabecalho = conversa.cabecalho();
        Texto assunto = mascarar(cabecalho.assunto(), false);
        Texto resumo = mascarar(cabecalho.resumo(), false);
        valores.addAll(assunto.valores());
        valores.addAll(resumo.valores());
        omitidos = omitidos.mais(new Omitidos(assunto.chaveDoBot() + resumo.chaveDoBot(),
                assunto.acessoRemoto() + resumo.acessoRemoto()));
        return new Resultado(conversa.com(cabecalho.comTextos(assunto.texto(), resumo.texto()), itens), omitidos, valores);
    }

    /** Um texto solto (sem o contexto da mensagem anterior). */
    public String mascarar(String texto) {
        return mascarar(texto, false).texto();
    }

    Texto mascarar(String texto, boolean depoisDoId) {
        if (texto == null || texto.isBlank()) {
            return new Texto(texto, 0, 0, false, List.of());
        }
        List<String> valores = new ArrayList<>();
        int[] chaveDoBot = {0};
        int[] senhas = {0};
        int[] ids = {0};
        String s = trocar(texto, CHAVE_DO_MES, valores, chaveDoBot);
        s = trocar(s, CHAVE_TEMPORARIA, valores, chaveDoBot);
        s = trocar(s, SENHA, valores, senhas);
        s = trocar(s, ID_COM_FERRAMENTA, valores, ids);
        String limpo = s.strip();
        if (ID_SOLTO.matcher(limpo).matches() && entre(digitos(limpo), 8, 10)) {
            valores.add(limpo);
            s = OMITIDO;
            ids[0]++;
        } else if (depoisDoId && PALAVRA_COM_NUMERO.matcher(limpo).matches()) {
            valores.add(limpo);
            s = OMITIDO;
            senhas[0]++;
        }
        return new Texto(s, chaveDoBot[0], senhas[0] + ids[0], ids[0] > 0 && senhas[0] == 0, valores);
    }

    private static String trocar(String texto, Pattern regra, List<String> valores, int[] contador) {
        Matcher m = regra.matcher(texto);
        StringBuilder saida = new StringBuilder();
        while (m.find()) {
            valores.add(m.group(2));
            contador[0]++;
            m.appendReplacement(saida, Matcher.quoteReplacement(m.group(1) + OMITIDO));
        }
        m.appendTail(saida);
        return saida.toString();
    }

    private static int digitos(String texto) {
        return (int) texto.chars().filter(Character::isDigit).count();
    }

    private static boolean entre(int valor, int minimo, int maximo) {
        return valor >= minimo && valor <= maximo;
    }
}
