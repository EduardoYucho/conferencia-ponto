import { ref } from 'vue'

/**
 * Tema da interface: claro, escuro ou automático (segue o sistema operacional). A escolha fica guardada no
 * navegador de cada pessoa. O tema é aplicado no <html data-theme="light|dark">; as cores vêm do style.css.
 */
const CHAVE = 'conferencia-ponto.tema'

export const TEMAS = [
  { valor: 'claro', rotulo: 'Claro' },
  { valor: 'escuro', rotulo: 'Escuro' },
  { valor: 'auto', rotulo: 'Automático' },
]

function lerPreferencia() {
  try {
    const salvo = localStorage.getItem(CHAVE)
    if (TEMAS.some((t) => t.valor === salvo)) return salvo
  } catch {
    // sem armazenamento (modo privado): vale o padrão
  }
  return 'claro'
}

const sistemaEscuro = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null

/** O que a pessoa escolheu: 'claro' | 'escuro' | 'auto'. */
export const tema = ref(lerPreferencia())
/** O tema em uso agora (resolvido o "automático"). */
export const escuro = ref(false)

function aplicar() {
  escuro.value = tema.value === 'escuro' || (tema.value === 'auto' && !!sistemaEscuro?.matches)
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = escuro.value ? 'dark' : 'light'
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', escuro.value ? '#171d26' : '#ffffff')
}

export function definirTema(valor) {
  if (!TEMAS.some((t) => t.valor === valor)) return
  tema.value = valor
  try {
    localStorage.setItem(CHAVE, valor)
  } catch {
    // sem armazenamento: vale até fechar a aba
  }
  aplicar()
}

/** Chamado uma vez, na subida da aplicação. */
export function iniciarTema() {
  aplicar()
  sistemaEscuro?.addEventListener?.('change', aplicar)
}
