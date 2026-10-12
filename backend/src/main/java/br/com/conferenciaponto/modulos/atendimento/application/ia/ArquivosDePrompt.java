package br.com.conferenciaponto.modulos.atendimento.application.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Os prompts e os esquemas JSON ficam em arquivos ({@code resources/atendimento/prompts/<nome>-v<N>.txt} e
 * {@code resources/atendimento/esquemas/<nome>-v<N>.json}), e não no código. A versão vai gravada com cada análise
 * e cada saída: mudar um prompt é criar o arquivo da versão seguinte, nunca editar o de uma versão já usada.
 */
public final class ArquivosDePrompt {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ArquivosDePrompt() {
    }

    /** O texto do prompt (sem a quebra de linha do fim do arquivo). */
    public static String prompt(String nome, int versao) {
        return recurso("atendimento/prompts/" + nome + "-v" + versao + ".txt");
    }

    /** O esquema JSON da resposta, como texto (vai assim para o Gemini). */
    public static String esquema(String nome, int versao) {
        String texto = recurso("atendimento/esquemas/" + nome + "-v" + versao + ".json");
        try {
            JSON.readTree(texto); // um esquema quebrado aparece na subida, não no meio de um atendimento
        } catch (IOException e) {
            throw new IllegalStateException("O esquema " + nome + "-v" + versao + " não é um JSON válido", e);
        }
        return texto;
    }

    /** Os campos obrigatórios do topo do esquema ("required"). */
    public static List<String> obrigatorios(String esquema) {
        List<String> campos = new ArrayList<>();
        json(esquema).path("required").forEach(c -> campos.add(c.asText()));
        return List.copyOf(campos);
    }

    /** Quais dos campos obrigatórios são listas ("type": "array"), na ordem do esquema. */
    public static List<String> listas(String esquema) {
        JsonNode raiz = json(esquema);
        List<String> obrigatorios = obrigatorios(esquema);
        List<String> listas = new ArrayList<>();
        for (Map.Entry<String, JsonNode> campo : raiz.path("properties").properties()) {
            if (obrigatorios.contains(campo.getKey()) && "array".equals(campo.getValue().path("type").asText())) {
                listas.add(campo.getKey());
            }
        }
        return List.copyOf(listas);
    }

    private static JsonNode json(String esquema) {
        try {
            return JSON.readTree(esquema);
        } catch (IOException e) {
            throw new IllegalArgumentException("Esquema JSON inválido", e);
        }
    }

    private static String recurso(String caminho) {
        try (InputStream entrada = ArquivosDePrompt.class.getClassLoader().getResourceAsStream(caminho)) {
            if (entrada == null) {
                throw new IllegalStateException("Arquivo de prompt não encontrado: " + caminho);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler " + caminho, e);
        }
    }
}
