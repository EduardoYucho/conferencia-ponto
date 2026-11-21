/**
 * Módulos que vivem dentro da Conferência de Ponto sem fazer parte dela: <b>atendimento</b> (gerador de textos
 * de atendimento com o Gemini), <b>conhecimento</b> (base de conhecimento alimentada por esses textos),
 * <b>fiscal</b> (monitor da situação da SEFAZ por estado e documento) e <b>backup</b> (backups de clientes recebidos
 * pelo navegador).
 *
 * <p>Regras (conferidas pelo ArquiteturaModulosTest):
 * <ul>
 *   <li>o ponto não usa nada daqui;</li>
 *   <li>os módulos usam do ponto só o usuário logado (com as permissões dele), o envelope de resposta com o
 *       protocolo de erro, as exceções do domínio, o log por usuário e a interface com que cada módulo declara as
 *       próprias permissões ({@code FonteDePermissoes}), que aparecem na tela de perfis do ponto;</li>
 *   <li>{@code conhecimento} conhece só os eventos publicados por {@code atendimento}; {@code atendimento} não
 *       conhece {@code conhecimento};</li>
 *   <li>{@code fiscal} não conhece os outros dois módulos, nem eles a ele;</li>
 *   <li>{@code backup} não conhece os outros módulos, nem eles a ele.</li>
 * </ul>
 * Cada módulo tem as próprias tabelas (schemas {@code atendimento}, {@code conhecimento} e {@code fiscal}; o
 * {@code backup} guarda o estado em arquivos, ao lado dos backups), rotas e telas: dá para extraí-los do ponto no
 * futuro.
 */
package br.com.expedio.modulos;
