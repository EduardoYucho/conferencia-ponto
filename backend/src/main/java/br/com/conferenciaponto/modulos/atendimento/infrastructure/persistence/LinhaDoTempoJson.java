package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A coluna atendimento.linha_do_tempo (jsonb). Formato:
 *
 * <pre>
 * { "versao": 1, "montadaEm",
 *   "itens":      [ { "id", "tipo": "mensagem|evento|arquivo", "momento", "lado", "remetente", "texto",
 *                     "arquivoId", "origem", "categoria", "nome", "mensagem", "situacao", "resumo" } ],
 *   "semHorario": [ ...os mesmos campos ] }
 * </pre>
 */
final class LinhaDoTempoJson {

    private final ObjectMapper json;

    LinhaDoTempoJson(ObjectMapper json) {
        this.json = json;
    }

    String escrever(LinhaDoTempo linha) {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("versao", linha.versao());
        raiz.put("montadaEm", linha.montadaEm() == null ? null : linha.montadaEm().toString());
        escrever(raiz.putArray("itens"), linha.itens());
        escrever(raiz.putArray("semHorario"), linha.semHorario());
        try {
            return json.writeValueAsString(raiz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível montar o JSON da linha do tempo", e);
        }
    }

    LinhaDoTempo ler(String texto) {
        JsonNode raiz;
        try {
            raiz = json.readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("O JSON da linha do tempo guardada está ilegível", e);
        }
        String montadaEm = valor(raiz, "montadaEm");
        return new LinhaDoTempo(raiz.path("versao").asInt(LinhaDoTempo.VERSAO), montadaEm == null ? null : Instant.parse(montadaEm),
                itens(raiz.path("itens")), itens(raiz.path("semHorario")));
    }

    private static void escrever(ArrayNode lista, List<ItemDaLinha> itens) {
        for (ItemDaLinha i : itens) {
            ObjectNode item = lista.addObject();
            item.put("id", i.id());
            item.put("tipo", i.tipo().codigo());
            item.put("momento", i.momento() == null ? null : i.momento().toString());
            item.put("lado", i.lado());
            item.put("remetente", i.remetente());
            item.put("texto", i.texto());
            item.put("arquivoId", i.arquivoId() == null ? null : i.arquivoId().toString());
            item.put("origem", i.origem());
            item.put("categoria", i.categoria());
            item.put("nome", i.nome());
            item.put("mensagem", i.mensagem());
            item.put("situacao", i.situacao());
            item.put("resumo", i.resumo());
        }
    }

    private static List<ItemDaLinha> itens(JsonNode lista) {
        List<ItemDaLinha> itens = new ArrayList<>();
        for (JsonNode i : lista) {
            String momento = valor(i, "momento");
            String arquivo = valor(i, "arquivoId");
            itens.add(new ItemDaLinha(valor(i, "id"), ItemDaLinha.TipoDoItem.valueOf(i.path("tipo").asText().toUpperCase(Locale.ROOT)),
                    momento == null ? null : Instant.parse(momento), valor(i, "lado"), valor(i, "remetente"), valor(i, "texto"),
                    arquivo == null ? null : UUID.fromString(arquivo), valor(i, "origem"), valor(i, "categoria"),
                    valor(i, "nome"), valor(i, "mensagem"), valor(i, "situacao"), valor(i, "resumo")));
        }
        return itens;
    }

    private static String valor(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }
}
