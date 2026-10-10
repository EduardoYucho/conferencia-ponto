package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mascara a resposta do Gemini antes de gravar, mesmo com a instrução de não reproduzir dados de acesso: o texto
 * passa pelas regras do {@link Mascaramento} e, nos pares rótulo/valor, o valor de um campo de senha, token ou ID de
 * acesso remoto vira {@value Mascaramento#OMITIDO} (a chave de acesso de uma nota fiscal fica: é do suporte).
 */
final class MascaraDaAnalise {

    private static final Pattern ROTULO_SENSIVEL = Pattern.compile(
            "(?iu)\\b(senha|password|pass|pin|token|any\\s*desk|ultra\\s*viewer|team\\s*viewer|rust\\s*desk"
                    + "|acesso\\s+remoto)\\b");
    /** "ID", "Seu ID", "ID do parceiro": o ID da ferramenta de acesso remoto (um "ID do pedido" fica). */
    private static final Pattern ROTULO_DE_ID = Pattern.compile("(?iu)^\\s*(seu\\s+)?id(\\s+(do\\s+)?parceiro)?\\s*:?\\s*$");

    private final Mascaramento mascaramento = new Mascaramento();

    static boolean sensivel(String rotulo) {
        return ROTULO_SENSIVEL.matcher(rotulo).find() || ROTULO_DE_ID.matcher(rotulo).matches();
    }

    /** Mascara no lugar e devolve o mesmo nó. */
    JsonNode aplicar(JsonNode no) {
        if (no instanceof ObjectNode objeto) {
            boolean sensivel = objeto.has("rotulo") && objeto.has("valor")
                    && sensivel(objeto.path("rotulo").asText(""))
                    && objeto.path("valor").asText("").chars().anyMatch(Character::isDigit);
            for (Map.Entry<String, JsonNode> campo : objeto.properties()) {
                if (sensivel && "valor".equals(campo.getKey())) {
                    campo.setValue(TextNode.valueOf(Mascaramento.OMITIDO));
                } else if (campo.getValue().isTextual()) {
                    campo.setValue(TextNode.valueOf(mascaramento.mascararTrechos(campo.getValue().asText())));
                } else {
                    aplicar(campo.getValue());
                }
            }
        } else if (no instanceof ArrayNode lista) {
            for (int i = 0; i < lista.size(); i++) {
                JsonNode item = lista.get(i);
                if (item.isTextual()) {
                    lista.set(i, TextNode.valueOf(mascaramento.mascararTrechos(item.asText())));
                } else {
                    aplicar(item);
                }
            }
        }
        return no;
    }
}
