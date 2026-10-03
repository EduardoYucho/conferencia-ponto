import { onBeforeUnmount, ref } from 'vue'

/**
 * Carga de uma tela que vem pronta do servidor (Início, Meu ponto, Banco de horas).
 *
 * - `carregar()`: a pessoa pediu (abriu a tela, trocou de mês, clicou em "Tentar de novo"): mostra "carregando" e,
 *   se falhar, o erro com "Tentar de novo".
 * - `carregar({ silencioso: true })`: atualização automática (algo mudou, a aba voltou, passou o tempo): o que já
 *   está na tela continua e, se falhar, a tela tenta de novo sozinha em alguns segundos — sem piscar nem assustar.
 * Só a resposta do último pedido entra na tela (trocando de mês depressa, elas podem chegar fora de ordem).
 *
 * @param {() => Promise<any>} buscar chamada da API
 * @param {(dados: any) => void} [aoReceber] chamado com os dados que entraram na tela
 */
export function useCarga(buscar, aoReceber) {
  const tela = ref(null)
  const carregando = ref(false)
  const erro = ref(null)
  let pedido = 0
  let timer = null

  async function carregar({ silencioso = false } = {}) {
    const meu = ++pedido
    clearTimeout(timer)
    if (!silencioso) carregando.value = true
    try {
      const dados = await buscar()
      if (meu !== pedido) return
      tela.value = dados
      erro.value = null
      aoReceber?.(dados)
    } catch (e) {
      if (meu !== pedido) return
      if (!silencioso || !tela.value) erro.value = e
      else timer = setTimeout(() => carregar({ silencioso: true }), 5000)
    } finally {
      if (meu === pedido) carregando.value = false
    }
  }

  onBeforeUnmount(() => {
    pedido++ // respostas que ainda estão a caminho não entram numa tela que já saiu
    clearTimeout(timer)
  })

  return { tela, carregando, erro, carregar }
}
