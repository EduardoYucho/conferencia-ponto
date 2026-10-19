/**
 * Os códigos das permissões do ponto e da administração (os mesmos do servidor: PermissoesDoPonto e
 * PermissoesDaAdministracao). As dos módulos ficam em src/modulos/acessoAosModulos.js. O menu e as telas decidem
 * por elas; quem garante é o servidor, que confere cada uma.
 */
export const PONTO_PROPRIO = 'ponto.proprio'
export const PONTO_LANCAR = 'ponto.lancar'
export const PONTO_EQUIPE = 'ponto.equipe'
export const PONTO_CONFIGURAR_EQUIPE = 'ponto.equipe.configurar'
export const PONTO_FERIADOS = 'ponto.feriados'

export const USUARIOS = 'usuarios.gerenciar'
export const ACESSOS = 'acessos.gerenciar'
export const INTEGRACOES = 'integracoes.gerenciar'
export const LOGS = 'logs.ver'

/**
 * O que cada pessoa pode, dados os perfis e os ajustes (a mesma conta do servidor, para a tela mostrar na hora o
 * resultado de uma mudança antes de salvar): a soma dos perfis, mais o liberado, menos o retirado; o que ficou
 * sem o requisito não vale.
 *
 * @param {Array<{ codigo, requer }>} catalogo todas as permissões (as dos grupos, achatadas)
 * @param {Array<{ todas, permissoes }>} perfis os perfis da pessoa
 * @param {Record<string, boolean>} ajustes código → true (liberada) ou false (retirada)
 * @returns {Set<string>}
 */
export function efetivas(catalogo, perfis, ajustes) {
  const codigos = new Set(catalogo.map((p) => p.codigo))
  const somadas = new Set()
  for (const perfil of perfis) {
    for (const c of perfil.todas ? codigos : perfil.permissoes ?? []) if (codigos.has(c)) somadas.add(c)
  }
  for (const [c, liberada] of Object.entries(ajustes ?? {})) if (liberada && codigos.has(c)) somadas.add(c)
  for (const [c, liberada] of Object.entries(ajustes ?? {})) if (!liberada) somadas.delete(c)
  const requer = Object.fromEntries(catalogo.map((p) => [p.codigo, p.requer]))
  let tirou = true
  while (tirou) {
    tirou = false
    for (const c of [...somadas]) {
      if (requer[c] && !somadas.has(requer[c])) {
        somadas.delete(c)
        tirou = true
      }
    }
  }
  return somadas
}
