import { fetchEventSource } from '@microsoft/fetch-event-source'
import { http, tokenAtual } from '@/api/http'
import { relatar } from '@/utils/erros'

class ErroDefinitivo extends Error {}

/**
 * O canal SSE do gerador (GET /api/v1/atendimentos/eventos), separado do canal do ponto: só chegam os eventos dos
 * atendimentos da própria pessoa. Como o do ponto, vai por fetch (para levar o token) e reconecta sozinho; só a
 * sessão encerrada (401) ou o gerador retirado (403) encerram a assinatura.
 *
 * @returns {() => void} encerra a assinatura
 */
export function conectarEventosDoGerador({ onProgresso = () => {}, onStatus = () => {} } = {}) {
  const url = `${http.defaults.baseURL.replace(/\/$/, '')}/atendimentos/eventos`
  const controle = new AbortController()
  let tentativas = 0
  onStatus('conectando')
  fetchEventSource(url, {
    signal: controle.signal,
    openWhenHidden: true,
    headers: { Authorization: `Bearer ${tokenAtual()}`, Accept: 'text/event-stream' },
    async onopen(resposta) {
      if (resposta.ok && resposta.headers.get('content-type')?.includes('text/event-stream')) {
        tentativas = 0
        onStatus('conectado')
        return
      }
      if (resposta.status === 401 || resposta.status === 403) {
        onStatus('encerrado')
        throw new ErroDefinitivo(`HTTP ${resposta.status}`)
      }
      throw new Error(`HTTP ${resposta.status}`)
    },
    onmessage(mensagem) {
      if (mensagem.event !== 'atendimento-progresso') return
      try {
        onProgresso(JSON.parse(mensagem.data))
      } catch (erro) {
        relatar(erro, 'tempo real do gerador')
      }
    },
    onclose() {
      throw new Error('conexão encerrada pelo servidor') // reconecta
    },
    onerror(erro) {
      if (erro instanceof ErroDefinitivo) throw erro
      onStatus('reconectando')
      tentativas++
      return Math.min(30000, 1000 * 2 ** Math.min(tentativas, 5))
    },
  }).catch(() => {})
  return () => controle.abort()
}
