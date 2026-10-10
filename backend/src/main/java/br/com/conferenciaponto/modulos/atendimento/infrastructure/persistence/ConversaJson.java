package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ConversaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Cabecalho;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ConversaLida;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ItemDaConversa;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Lado;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Omitidos;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.TipoDeItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A coluna atendimento.conversa (jsonb): cabeçalho, itens e quantos trechos foram omitidos. Formato:
 *
 * <pre>
 * { "formato": 1,
 *   "cabecalho": { "chamado", "contato", "inicio", "fim", "assunto", "resumo" },
 *   "itens": [ { "ordem", "tipo": "mensagem|evento", "lado": "cliente|atendente", "remetente", "momento",
 *                "texto", "anexos": [1, 2], "pagina" } ],
 *   "omitidos": { "chaveDoBot", "acessoRemoto" },
 *   "leitura": { "linksIgnorados", "linhasSemCabecalho" } }
 * </pre>
 *
 * As datas vão como texto ISO-8601 em UTC.
 */
final class ConversaJson {

    private static final int FORMATO = 1;

    private final ObjectMapper json;

    ConversaJson(ObjectMapper json) {
        this.json = json;
    }

    String escrever(ConversaLida conversa, Omitidos omitidos) {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("formato", FORMATO);
        Cabecalho c = conversa.cabecalho();
        ObjectNode cabecalho = raiz.putObject("cabecalho");
        cabecalho.put("chamado", c.chamado());
        cabecalho.put("contato", c.contato());
        cabecalho.put("inicio", texto(c.inicio()));
        cabecalho.put("fim", texto(c.fim()));
        cabecalho.put("assunto", c.assunto());
        cabecalho.put("resumo", c.resumo());
        ArrayNode itens = raiz.putArray("itens");
        for (ItemDaConversa i : conversa.itens()) {
            ObjectNode item = itens.addObject();
            item.put("ordem", i.ordem());
            item.put("tipo", i.tipo().codigo());
            item.put("lado", i.lado() == null ? null : i.lado().codigo());
            item.put("remetente", i.remetente());
            item.put("momento", texto(i.momento()));
            item.put("texto", i.texto());
            ArrayNode anexos = item.putArray("anexos");
            i.anexos().forEach(anexos::add);
            item.put("pagina", i.pagina());
        }
        ObjectNode omitidosJson = raiz.putObject("omitidos");
        omitidosJson.put("chaveDoBot", omitidos.chaveDoBot());
        omitidosJson.put("acessoRemoto", omitidos.acessoRemoto());
        ObjectNode leitura = raiz.putObject("leitura");
        leitura.put("linksIgnorados", conversa.linksIgnorados());
        leitura.put("linhasSemCabecalho", conversa.linhasSemCabecalho());
        try {
            return json.writeValueAsString(raiz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível montar o JSON da conversa", e);
        }
    }

    ConversaGuardada ler(String texto) {
        JsonNode raiz;
        try {
            raiz = json.readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("O JSON da conversa guardada está ilegível", e);
        }
        JsonNode c = raiz.path("cabecalho");
        Cabecalho cabecalho = new Cabecalho(textoOuNulo(c, "chamado"), textoOuNulo(c, "contato"), instante(c, "inicio"),
                instante(c, "fim"), textoOuNulo(c, "assunto"), textoOuNulo(c, "resumo"));
        List<ItemDaConversa> itens = new ArrayList<>();
        for (JsonNode i : raiz.path("itens")) {
            List<Integer> anexos = new ArrayList<>();
            i.path("anexos").forEach(a -> anexos.add(a.asInt()));
            String lado = textoOuNulo(i, "lado");
            itens.add(new ItemDaConversa(i.path("ordem").asInt(), TipoDeItem.doCodigo(i.path("tipo").asText()),
                    Lado.doCodigo(lado), textoOuNulo(i, "remetente"), instante(i, "momento"), textoOuNulo(i, "texto"),
                    anexos, i.path("pagina").asInt()));
        }
        JsonNode o = raiz.path("omitidos");
        return new ConversaGuardada(cabecalho, itens, new Omitidos(o.path("chaveDoBot").asInt(), o.path("acessoRemoto").asInt()));
    }

    private static String texto(Instant instante) {
        return instante == null ? null : instante.toString();
    }

    private static String textoOuNulo(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }

    private static Instant instante(JsonNode no, String campo) {
        String valor = textoOuNulo(no, campo);
        return valor == null ? null : Instant.parse(valor);
    }
}
