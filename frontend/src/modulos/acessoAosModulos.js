import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { atendimentoApi } from './atendimento/api'

/** As permissões dos módulos (as mesmas do servidor: PermissoesDoAtendimento, PermissoesDaBase e PermissoesDoMonitor). */
export const ATENDIMENTOS_USAR = 'atendimentos.usar'
export const ATENDIMENTOS_ESPACO = 'atendimentos.espaco'
export const BASE_PESQUISAR = 'base.pesquisar'
export const BASE_CURAR = 'base.curar'
export const FISCAL_VER = 'fiscal.ver'
export const FISCAL_CONFIGURAR = 'fiscal.configurar'

/**
 * O que a pessoa logada pode usar nos módulos (atendimentos, base de conhecimento, monitor fiscal). Vem das permissões dela
 * (os perfis e os ajustes, em Usuários): o menu e as telas decidem por isto; quem garante é o servidor (cada rota
 * dos módulos confere a permissão). Do servidor vem só se os textos são feitos pela IA local.
 */
export const useAcessoModulosStore = defineStore('acessoModulos', () => {
  const auth = useAuthStore()
  const gerador = computed(() => auth.pode(ATENDIMENTOS_USAR))
  const espaco = computed(() => auth.pode(ATENDIMENTOS_ESPACO))
  const pesquisar = computed(() => auth.pode(BASE_PESQUISAR))
  const curar = computed(() => auth.pode(BASE_CURAR))
  const fiscal = computed(() => auth.pode(FISCAL_VER))
  const configurarFiscal = computed(() => auth.pode(FISCAL_CONFIGURAR))
  /** Os textos são feitos pela IA local deste computador (sem chave do Gemini, sem cota). */
  const iaLocal = ref(true)
  /** Já perguntou ao servidor ao menos uma vez nesta sessão. */
  const carregado = ref(false)
  const erro = ref(null)

  /** Uma falha mantém o que já se sabia (e fica em `erro`, para a tela oferecer "Tentar de novo"). */
  async function carregar() {
    try {
      const doGerador = await atendimentoApi.meuAcesso()
      iaLocal.value = doGerador?.iaLocal !== false
      erro.value = null
    } catch (e) {
      erro.value = e
    } finally {
      carregado.value = true
    }
  }

  function limpar() {
    carregado.value = false
    erro.value = null
  }

  return { gerador, espaco, pesquisar, curar, fiscal, configurarFiscal, iaLocal, carregado, erro, carregar, limpar }
})
