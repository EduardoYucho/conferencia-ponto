package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A análise de um arquivo (o JSON do esquema de cada tipo) em texto, para a linha do tempo: é assim que o redator lê
 * o que estava no print, no áudio ou no documento. Os campos "não identificado" ficam de fora (exceto a transcrição,
 * que vai inteira).
 */
final class TextoDaAnalise {

    /** Documento longo: o redator recebe o começo (o resto custaria tokens sem mudar o resumo). */
    static final int MAXIMO_DO_CONTEUDO = 20_000;
    private static final int MAXIMO_DO_RESUMO = 140;

    private TextoDaAnalise() {
    }

    static String texto(String categoria, JsonNode analise) {
        return switch (categoria == null ? "" : categoria) {
            case "imagem" -> imagem(analise);
            case "audio" -> audio(analise);
            case "documento" -> documento(analise);
            default -> "";
        };
    }

    /** Uma linha sobre o arquivo: a tela do print ("Pedido de Venda (EQR76)"), o tipo do documento. */
    static String resumo(String categoria, JsonNode analise) {
        String resumo = switch (categoria == null ? "" : categoria) {
            case "imagem" -> {
                JsonNode tela = analise.path("tela");
                String nome = valor(tela.path("nome"));
                String codigo = valor(tela.path("codigo"));
                if (nome != null) {
                    yield codigo != null && !nome.contains(codigo) ? "tela " + nome + " (" + codigo + ")" : "tela " + nome;
                }
                yield primeiraFrase(valor(analise.path("descricao")));
            }
            case "documento" -> {
                String tipo = valor(analise.path("tipoDocumento"));
                String descricao = primeiraFrase(valor(analise.path("descricao")));
                yield tipo != null ? tipo + (descricao != null ? ": " + descricao : "") : descricao;
            }
            case "audio" -> {
                String duracao = valor(analise.path("duracao"));
                yield duracao != null ? "transcrição de " + duracao : "transcrição";
            }
            default -> null;
        };
        if (resumo == null) {
            return null;
        }
        return resumo.length() <= MAXIMO_DO_RESUMO ? resumo : resumo.substring(0, MAXIMO_DO_RESUMO - 1).strip() + "…";
    }

    private static String imagem(JsonNode a) {
        List<String> linhas = new ArrayList<>();
        String descricao = valor(a.path("descricao"));
        if (descricao != null) {
            linhas.add("O que mostra: " + descricao);
        }
        JsonNode tela = a.path("tela");
        List<String> partes = new ArrayList<>();
        adicionar(partes, "tela ", tela.path("nome"));
        adicionar(partes, "código ", tela.path("codigo"));
        adicionar(partes, "menu ", tela.path("caminhoMenu"));
        if (!partes.isEmpty()) {
            linhas.add("Tela: " + String.join(" · ", partes).replaceFirst("^tela ", ""));
        }
        List<String> mensagens = new ArrayList<>();
        for (JsonNode m : a.path("mensagens")) {
            String texto = valor(m.path("texto"));
            if (texto != null) {
                String tipo = valor(m.path("tipo"));
                mensagens.add("- " + (tipo == null || tipo.equals("outro") ? "" : "(" + tipoDeMensagem(tipo) + ") ") + "\"" + texto + "\"");
            }
        }
        if (!mensagens.isEmpty()) {
            linhas.add("Mensagens na tela:");
            linhas.addAll(mensagens);
        }
        List<String> campos = paresRotuloValor(a.path("campos"));
        if (!campos.isEmpty()) {
            linhas.add("Campos e valores:");
            linhas.addAll(campos);
        }
        if (a.path("textoLegivel").isBoolean() && !a.path("textoLegivel").asBoolean()) {
            linhas.add("O texto da imagem não estava legível.");
        }
        return String.join("\n", linhas);
    }

    private static String audio(JsonNode a) {
        Map<String, String> papeis = new HashMap<>();
        for (JsonNode f : a.path("falantes")) {
            String papel = valor(f.path("papel"));
            if (papel != null && !papel.equals("nao_identificado")) {
                papeis.put(f.path("rotulo").asText(""), papel.equals("outro") ? "outra pessoa" : papel);
            }
        }
        List<String> linhas = new ArrayList<>();
        String duracao = valor(a.path("duracao"));
        boolean inaudivel = a.path("temTrechoInaudivel").asBoolean(false);
        linhas.add("Transcrição" + (duracao != null ? " (duração " + duracao + ")" : "")
                + (inaudivel ? ", com trechos inaudíveis marcados como [inaudível]" : "") + ":");
        int falas = 0;
        for (JsonNode t : a.path("trechos")) {
            String falante = t.path("falante").asText("").strip();
            String papel = papeis.get(falante);
            linhas.add("[" + t.path("inicio").asText("").strip() + "] " + (falante.isEmpty() ? "Falante" : falante)
                    + (papel != null ? " (" + papel + ")" : "") + ": " + t.path("texto").asText("").strip());
            falas++;
        }
        if (falas == 0) {
            linhas.add("(nenhuma fala identificada no áudio)");
        }
        return String.join("\n", linhas);
    }

    private static String documento(JsonNode a) {
        List<String> linhas = new ArrayList<>();
        String tipo = valor(a.path("tipoDocumento"));
        String descricao = valor(a.path("descricao"));
        if (tipo != null || descricao != null) {
            linhas.add("Documento: " + (tipo != null ? tipo : "") + (tipo != null && descricao != null ? " — " : "")
                    + (descricao != null ? descricao : ""));
        }
        List<String> mensagens = new ArrayList<>();
        for (JsonNode m : a.path("mensagens")) {
            String texto = valor(m);
            if (texto != null) {
                mensagens.add("- \"" + texto + "\"");
            }
        }
        if (!mensagens.isEmpty()) {
            linhas.add("Mensagens de erro ou aviso:");
            linhas.addAll(mensagens);
        }
        List<String> dados = paresRotuloValor(a.path("dados"));
        if (!dados.isEmpty()) {
            linhas.add("Dados:");
            linhas.addAll(dados);
        }
        String conteudo = valor(a.path("conteudo"));
        if (conteudo != null) {
            linhas.add("Conteúdo:");
            linhas.add(conteudo.length() <= MAXIMO_DO_CONTEUDO ? conteudo
                    : conteudo.substring(0, MAXIMO_DO_CONTEUDO) + "\n(… o restante do documento foi cortado)");
        }
        return String.join("\n", linhas);
    }

    private static List<String> paresRotuloValor(JsonNode lista) {
        List<String> linhas = new ArrayList<>();
        for (JsonNode c : lista) {
            String rotulo = valor(c.path("rotulo"));
            String valor = valor(c.path("valor"));
            if (rotulo != null || valor != null) {
                linhas.add("- " + (rotulo != null ? rotulo : "(sem rótulo)") + ": " + (valor != null ? valor : "não identificado"));
            }
        }
        return linhas;
    }

    private static void adicionar(List<String> partes, String rotulo, JsonNode campo) {
        String v = valor(campo);
        if (v != null) {
            partes.add(rotulo + v);
        }
    }

    private static String tipoDeMensagem(String tipo) {
        return switch (tipo) {
            case "erro" -> "erro";
            case "aviso" -> "aviso";
            case "informacao" -> "informação";
            case "confirmacao" -> "confirmação";
            default -> tipo;
        };
    }

    /** O texto do campo, ou null se vazio ou "não identificado". */
    static String valor(JsonNode campo) {
        if (campo == null || campo.isMissingNode() || campo.isNull()) {
            return null;
        }
        String texto = campo.isTextual() ? campo.asText() : campo.toString();
        texto = texto.strip();
        String minusculo = texto.toLowerCase(Locale.ROOT);
        return texto.isEmpty() || minusculo.equals("não identificado") || minusculo.equals("nao identificado") ? null : texto;
    }

    private static String primeiraFrase(String texto) {
        if (texto == null) {
            return null;
        }
        int ponto = texto.indexOf(". ");
        return ponto > 0 ? texto.substring(0, ponto) : texto.replaceFirst("\\.$", "");
    }
}
