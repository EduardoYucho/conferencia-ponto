import { defineStore } from 'pinia'
import { ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'

/** Sino do painel: avisos de prazo do banco de horas e da conciliação com o RH. */
export const useNotificacoesStore = defineStore('notificacoes', () => {
  const lista = ref([])
  const naoLidas = ref(0)
  const carregado = ref(false)
  /** Última notificação que chegou em tempo real (a barra mostra um aviso). */
  const recemChegada = ref(null)

  function aplicar(dados) {
    lista.value = dados.notificacoes
    naoLidas.value = dados.naoLidas
    carregado.value = true
  }

  async function carregar() {
    aplicar(await pontoApi.notificacoes())
  }

  /** Evento SSE "notificacao". */
  function receber({ notificacao, naoLidas: total }) {
    lista.value = [notificacao, ...lista.value.filter((n) => n.id !== notificacao.id)].slice(0, 30)
    naoLidas.value = total
    recemChegada.value = { ...notificacao, recebidaEm: Date.now() }
  }

  async function marcarLida(id) {
    aplicar(await pontoApi.marcarNotificacaoLida(id))
  }

  async function marcarTodas() {
    aplicar(await pontoApi.marcarNotificacoesLidas())
  }

  function limpar() {
    lista.value = []
    naoLidas.value = 0
    carregado.value = false
    recemChegada.value = null
  }

  return { lista, naoLidas, carregado, recemChegada, carregar, receber, marcarLida, marcarTodas, limpar }
})
