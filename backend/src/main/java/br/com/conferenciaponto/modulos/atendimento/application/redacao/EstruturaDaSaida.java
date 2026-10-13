package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.BlocoDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O texto conferido em JSON (a coluna saida.estrutura):
 *
 * <pre>
 * { "versao": 1, "assunto",
 *   "blocos": [ { "tipo": "paragrafo|passo", "secao" (chamados), "doSistema" (se true), "texto", "fontes": ["M2", "A1"],
 *                 "imagens": [ { "id": "A1", "imagem": "&lt;uuid&gt;", "numero": 1, "legenda" } ] } ],
 *   "base": { "cliente", "modulo", ... }, "telaFuncionalidade", "avisos": [], "variantes": [] }
 * </pre>
 */
final class EstruturaDaSaida {

    private EstruturaDaSaida() {
    }

    static String escrever(ObjectMapper json, TextoRedigido texto) {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("versao", texto.versao());
        raiz.put("assunto", texto.assunto());
        ArrayNode blocos = raiz.putArray("blocos");
        for (BlocoDoTexto b : texto.blocos()) {
            ObjectNode bloco = blocos.addObject();
            bloco.put("tipo", b.tipo().codigo());
            if (b.secao() != null) {
                bloco.put("secao", b.secao());
            }
            if (b.doSistema()) {
                bloco.put("doSistema", true);
            }
            bloco.put("texto", b.texto());
            ArrayNode fontes = bloco.putArray("fontes");
            b.fontes().forEach(fontes::add);
            ArrayNode imagens = bloco.putArray("imagens");
            for (BlocoDoTexto.ImagemCitada i : b.imagens()) {
                imagens.addObject().put("id", i.id()).put("imagem", i.imagem().toString()).put("numero", i.numero())
                        .put("legenda", i.legenda());
            }
        }
        ObjectNode base = raiz.putObject("base");
        texto.base().forEach(base::put);
        raiz.put("telaFuncionalidade", texto.telaFuncionalidade());
        ArrayNode avisos = raiz.putArray("avisos");
        texto.avisos().forEach(avisos::add);
        ArrayNode variantes = raiz.putArray("variantes");
        texto.variantes().forEach(variantes::add);
        if (!texto.semAnalise().isEmpty()) {
            ArrayNode sem = raiz.putArray("semAnalise");
            texto.semAnalise().forEach(id -> sem.add(id.toString()));
        }
        try {
            return json.writeValueAsString(raiz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível montar o JSON do texto", e);
        }
    }

    static TextoRedigido ler(ObjectMapper json, String texto) {
        if (texto == null || texto.isBlank()) {
            return new TextoRedigido(TextoRedigido.VERSAO, null, List.of(), Map.of(), null, List.of());
        }
        JsonNode raiz;
        try {
            raiz = json.readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("O JSON do texto guardado está ilegível", e);
        }
        List<BlocoDoTexto> blocos = new ArrayList<>();
        for (JsonNode b : raiz.path("blocos")) {
            List<String> fontes = new ArrayList<>();
            b.path("fontes").forEach(f -> fontes.add(f.asText()));
            List<BlocoDoTexto.ImagemCitada> imagens = new ArrayList<>();
            for (JsonNode i : b.path("imagens")) {
                imagens.add(new BlocoDoTexto.ImagemCitada(i.path("id").asText(), UUID.fromString(i.path("imagem").asText()),
                        i.path("numero").asInt(), i.path("legenda").isNull() ? null : i.path("legenda").asText(null)));
            }
            blocos.add(new BlocoDoTexto(BlocoDoTexto.Tipo.doCodigo(b.path("tipo").asText()), b.path("texto").asText(""),
                    fontes, imagens, b.hasNonNull("secao") ? b.path("secao").asText() : null, b.path("doSistema").asBoolean(false)));
        }
        Map<String, String> base = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> campo : raiz.path("base").properties()) {
            base.put(campo.getKey(), campo.getValue().asText(""));
        }
        List<String> avisos = new ArrayList<>();
        raiz.path("avisos").forEach(a -> avisos.add(a.asText()));
        List<String> variantes = new ArrayList<>();
        raiz.path("variantes").forEach(v -> variantes.add(v.asText()));
        List<UUID> semAnalise = new ArrayList<>();
        raiz.path("semAnalise").forEach(id -> {
            try {
                semAnalise.add(UUID.fromString(id.asText()));
            } catch (IllegalArgumentException e) {
                // id ilegível: fica de fora (o texto continua valendo)
            }
        });
        return new TextoRedigido(raiz.path("versao").asInt(TextoRedigido.VERSAO), texto(raiz, "assunto"), blocos, base,
                texto(raiz, "telaFuncionalidade"), avisos, variantes, semAnalise);
    }

    private static String texto(JsonNode raiz, String campo) {
        JsonNode v = raiz.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }
}
