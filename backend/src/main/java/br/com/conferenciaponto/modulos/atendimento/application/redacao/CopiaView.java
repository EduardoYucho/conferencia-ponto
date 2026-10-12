package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import java.util.List;

/**
 * O que vai para a área de transferência.
 *
 * @param html   o HTML (formatado nos modos com e sem imagens; no código-fonte, a tela copia como texto)
 * @param texto  o mesmo conteúdo em texto simples (para quem cola num campo sem formatação)
 * @param avisos imagens que não puderam ir (apagadas ou grandes demais)
 */
public record CopiaView(String modo, String html, String texto, List<String> avisos) {
}
