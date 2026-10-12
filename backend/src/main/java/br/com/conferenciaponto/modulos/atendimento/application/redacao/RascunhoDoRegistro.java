package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * O formulário do registro já preenchido, para o autor revisar antes de enviar o texto para a base: o título sai do
 * assunto (sem o "Digisac: contato :"), os campos saem do que o Gemini preparou para a base e o "não identificado"
 * fica em branco (para não entrar na pesquisa). O resumo entra como resolvido no suporte e os chamados, como
 * enviados ao desenvolvimento.
 */
final class RascunhoDoRegistro {

    private static final Pattern PREFIXO = Pattern.compile("^\\s*Digisac\\s*:\\s*[^:]*?\\s:\\s*");

    private RascunhoDoRegistro() {
    }

    static TextoConfirmado.Formulario de(SaidaGuardada saida, TextoRedigido texto, String contato) {
        Map<String, String> base = texto.base();
        String tela = valor(base.get("tela"));
        if (tela == null && saida.tipo().paraODesenvolvimento()) {
            tela = valor(texto.telaFuncionalidade());
        }
        return new TextoConfirmado.Formulario(titulo(saida.assunto(), contato), tipo(saida.tipo()), valor(base.get("cliente")),
                valor(contato), valor(base.get("modulo")), tela, valor(base.get("codigoTela")), valor(base.get("sintoma")),
                valor(base.get("mensagemErro")), valor(base.get("causa")), valor(base.get("resolucao")),
                saida.tipo().paraODesenvolvimento() ? "enviado_desenvolvimento" : "resolvido_suporte", null);
    }

    /** "Digisac: Maria : Erro ao gravar o pedido" → "Erro ao gravar o pedido". */
    static String titulo(String assunto, String contato) {
        if (assunto == null) {
            return null;
        }
        String limpo = assunto.strip();
        String prefixo = contato == null ? null : "Digisac: " + contato.strip() + " : ";
        if (prefixo != null && limpo.startsWith(prefixo)) {
            limpo = limpo.substring(prefixo.length());
        } else {
            limpo = PREFIXO.matcher(limpo).replaceFirst("");
        }
        return valor(limpo);
    }

    static String tipo(TipoDeSaida tipo) {
        return switch (tipo) {
            case RESUMO -> "duvida";
            case DEV_ERRO -> "erro";
            case DEV_ALTERACAO -> "alteracao";
        };
    }

    /** Vazio ou "não identificado" fica em branco. */
    static String valor(String texto) {
        if (texto == null) {
            return null;
        }
        String t = texto.strip();
        String minusculo = t.toLowerCase(Locale.ROOT).replaceAll("[.\\s]+$", "");
        return t.isEmpty() || minusculo.equals("não identificado") || minusculo.equals("nao identificado")
                || minusculo.equals("não informado") ? null : t;
    }
}
