package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconhece, pela descrição que a análise fez de uma imagem, a foto ou o print da tela do programa de acesso remoto
 * (UltraViewer, AnyDesk, TeamViewer...): ela mostra o ID e a senha, então não entra no texto, na base nem no pacote
 * para o Gemini. O print de uma tela do sistema feito pelo acesso remoto ("a tela de Pedidos, vista pelo AnyDesk")
 * continua valendo.
 */
public final class TelaDeAcessoRemoto {

    private static final String PROGRAMA = "(?:ultra\\s*viewer|any\\s*desk|team\\s*viewer|rust\\s*desk|aero\\s*admin"
            + "|supremo|logmein|splashtop|ammyy(?:\\s*admin)?|chrome\\s+remote\\s+desktop)";
    private static final Pattern TELA_DO_PROGRAMA = Pattern.compile(
            "(?iu)\\b(?:tela|janela|interface|software|programa|aplicativo|app|ferramenta)\\b([^.\\n]{0,45}?)\\b" + PROGRAMA + "\\b");
    /** "vista pelo AnyDesk": a tela é do sistema, só foi acessada pelo programa. */
    private static final Pattern PELO_PROGRAMA = Pattern.compile(
            "(?iu)\\b(?:via|pel[oa]|atrav[eé]s|por\\s+meio|dentro|sess[aã]o|conex[aã]o|conectad[oa])\\b");

    private TelaDeAcessoRemoto() {
    }

    /** A descrição (ou o resumo) da imagem diz que ela mostra a tela do programa de acesso remoto. */
    public static boolean mostra(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return false;
        }
        Matcher m = TELA_DO_PROGRAMA.matcher(descricao);
        while (m.find()) {
            if (!PELO_PROGRAMA.matcher(m.group(1)).find()) {
                return true;
            }
        }
        return false;
    }
}
