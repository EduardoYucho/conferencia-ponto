import { fetchEventSource } from '@microsoft/fetch-event-source'
import { http, tokenAtual } from './http'

class ErroDefinitivo extends Error {}

/**
 * Assinatura autenticada do stream SSE (GET /api/v1/eventos).
 *
 * O EventSource nativo não permite enviar o header Authorization, então o stream é lido
 * via fetch (@microsoft/fetch-event-source) com o Bearer token. Reconecta sozinho com
 * espera exponencial (1s → 30s); 401/403 encerram a assinatura.
 *
 * @returns {() => void} função que encerra a assinatura
 */
export function conectarEventos({
  onStatus = () => {},
  onConectado = () => {},
  onJornadaAtualizada = () => {},
  onComprovanteNaoImportado = () => {},
  onNaoAutorizado = () => {},
  onMonitor = () => {},
  onCiclo = () => {},
  onNotificacao = () => {},
  onConciliacao = () => {},
} = {}) {
  const url = `${http.defaults.baseURL.replace(/\/$/, '')}/eventos`
  const controle = new AbortController()
  let tentativas = 0

  const lerJson = (texto, callback) => {
    try {
      callback(JSON.parse(texto))
    } catch (erro) {
      console.error('[SSE] payload inválido', erro)
    }
  }

  onStatus('conectando')
  fetchEventSource(url, {
    signal: controle.signal,
    openWhenHidden: true, // continua recebendo com a aba em segundo plano
    headers: { Authorization: `Bearer ${tokenAtual()}`, Accept: 'text/event-stream' },

    async onopen(resposta) {
      if (resposta.ok && resposta.headers.get('content-type')?.includes('text/event-stream')) {
        tentativas = 0
        onStatus('conectado')
        return
      }
      if (resposta.status === 401 || resposta.status === 403) {
        onNaoAutorizado()
        throw new ErroDefinitivo(`HTTP ${resposta.status}`)
      }
      throw new Error(`HTTP ${resposta.status}`)
    },

    onmessage(mensagem) {
      if (mensagem.event === 'conectado') lerJson(mensagem.data, onConectado)
      else if (mensagem.event === 'jornada-atualizada') lerJson(mensagem.data, onJornadaAtualizada)
      else if (mensagem.event === 'comprovante-nao-importado') lerJson(mensagem.data, onComprovanteNaoImportado)
      else if (mensagem.event === 'monitor-atualizado') lerJson(mensagem.data, onMonitor)
      else if (mensagem.event === 'ciclo-atualizado') lerJson(mensagem.data, onCiclo)
      else if (mensagem.event === 'notificacao') lerJson(mensagem.data, onNotificacao)
      else if (mensagem.event === 'conciliacao-atualizada') lerJson(mensagem.data, onConciliacao)
    },

    onclose() {
      // o servidor encerrou (timeout da conexão): reconectar
      throw new Error('stream encerrado')
    },

    onerror(erro) {
      if (erro instanceof ErroDefinitivo || controle.signal.aborted) {
        onStatus('desconectado')
        throw erro // interrompe as novas tentativas
      }
      onStatus('reconectando')
      return Math.min(30_000, 1_000 * 2 ** tentativas++)
    },
  }).catch(() => {})

  return () => {
    controle.abort()
    onStatus('desconectado')
  }
}
