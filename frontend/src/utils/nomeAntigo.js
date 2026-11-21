/**
 * O Expedio se chamava "Conferência de Ponto": o que o navegador guardou com o nome antigo (a sessão, o menu
 * recolhido, a versão da habilidade do Gemini) passa para o nome novo, sem ninguém precisar entrar de novo.
 * Roda antes de as telas lerem o armazenamento (main.js).
 */
const ANTIGO = 'conferencia-ponto.'
const NOVO = 'expedio.'

export function migrarArmazenamentoLocal() {
  try {
    for (const chave of Object.keys(localStorage)) {
      if (!chave.startsWith(ANTIGO)) continue
      const nova = NOVO + chave.slice(ANTIGO.length)
      if (localStorage.getItem(nova) === null) localStorage.setItem(nova, localStorage.getItem(chave))
      localStorage.removeItem(chave)
    }
  } catch {
    // sem armazenamento (janela anônima, bloqueado): não há o que trazer
  }
}
