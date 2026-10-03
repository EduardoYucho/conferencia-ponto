import { reactive } from 'vue'

/**
 * Aviso rápido no canto da tela ("Batida registrada", "Não foi possível..."), o mesmo em todas as telas.
 * O de erro fica até a pessoa fechar; os outros somem sozinhos.
 */
export const aviso = reactive({ atual: null })
let timer = null

/** @param {'ok'|'erro'|'info'} tipo */
export function avisar(texto, tipo = 'ok') {
  clearTimeout(timer)
  aviso.atual = { texto, tipo, chave: Date.now() }
  if (tipo !== 'erro') timer = setTimeout(fecharAviso, 5000)
}

export function fecharAviso() {
  clearTimeout(timer)
  aviso.atual = null
}
