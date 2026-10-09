import { defineStore } from 'pinia'
import { ref } from 'vue'
import { atendimentoApi } from './atendimento/api'
import { conhecimentoApi } from './conhecimento/api'

/**
 * O que a pessoa logada pode usar nos módulos de atendimentos e base de conhecimento. O menu e as telas decidem
 * por isto; quem garante é o servidor (cada rota dos módulos confere a liberação). Carregado a cada login e de
 * novo quando o administrador muda o próprio acesso.
 */
export const useAcessoModulosStore = defineStore('acessoModulos', () => {
  const gerador = ref(false)
  const pesquisar = ref(false)
  const curar = ref(false)
  /** Já perguntou ao servidor ao menos uma vez nesta sessão. */
  const carregado = ref(false)
  const erro = ref(null)

  /** Uma falha mantém o que já se sabia (e fica em `erro`, para a tela oferecer "Tentar de novo"). */
  async function carregar() {
    const [doGerador, daBase] = await Promise.allSettled([atendimentoApi.meuAcesso(), conhecimentoApi.meuAcesso()])
    erro.value = null
    if (doGerador.status === 'fulfilled') {
      gerador.value = !!doGerador.value?.gerador
    } else {
      erro.value = doGerador.reason
    }
    if (daBase.status === 'fulfilled') {
      pesquisar.value = !!daBase.value?.pesquisar
      curar.value = !!daBase.value?.curar
    } else {
      erro.value = erro.value ?? daBase.reason
    }
    carregado.value = true
  }

  function limpar() {
    gerador.value = false
    pesquisar.value = false
    curar.value = false
    carregado.value = false
    erro.value = null
  }

  return { gerador, pesquisar, curar, carregado, erro, carregar, limpar }
})
