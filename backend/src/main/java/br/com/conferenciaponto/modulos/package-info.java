/**
 * Módulos que vivem dentro da Conferência de Ponto sem fazer parte dela: <b>atendimento</b> (gerador de textos
 * de atendimento com o Gemini) e <b>conhecimento</b> (base de conhecimento alimentada por esses textos).
 *
 * <p>Regras (conferidas pelo ArquiteturaModulosTest):
 * <ul>
 *   <li>o ponto não usa nada daqui;</li>
 *   <li>os módulos usam do ponto só o usuário logado, o envelope de resposta com o protocolo de erro, as
 *       exceções do domínio e o log por usuário;</li>
 *   <li>{@code conhecimento} conhece só os eventos publicados por {@code atendimento}; {@code atendimento} não
 *       conhece {@code conhecimento}.</li>
 * </ul>
 * Cada módulo tem as próprias tabelas (schemas {@code atendimento} e {@code conhecimento}), rotas e telas: dá para
 * extraí-los do ponto no futuro.
 */
package br.com.conferenciaponto.modulos;
