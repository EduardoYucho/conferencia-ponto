import axios from 'axios'
import { reactive } from 'vue'

/**
 * Erro normalizado a partir do envelope padrão da API:
 * { sucesso: false, dados: null, erros: [{ codigo, mensagem, campo }], protocolo, timestamp }
 *
 * A mensagem é sempre uma frase para a pessoa ler (nunca texto técnico). O que serve ao suporte fica nos
 * campos: `status`, `codigo`, `protocolo` (acha a linha no log do servidor) e `tipo`:
 *
 * - NEGOCIO: o servidor recusou e explicou por quê (regra, validação, permissão)
 * - SESSAO: sessão ausente, expirada ou acesso desativado
 * - REDE: o servidor não respondeu (sem rede, VPN caída, sistema parado)
 * - TEMPO: a resposta demorou além do limite
 * - INDISPONIVEL: o servidor respondeu que não pode atender agora (reiniciando, banco fora, ocupado)
 * - SERVIDOR: falha inesperada do lado do servidor
 * - RESPOSTA: a resposta não veio no formato esperado
 * - CANCELADA: o próprio navegador desistiu do pedido (saiu da tela)
 */
export class ApiError extends Error {
  constructor(mensagem, { status = null, erros = [], tipo = 'NEGOCIO', protocolo = null } = {}) {
    super(mensagem)
    this.name = 'ApiError'
    this.status = status
    this.erros = erros
    this.tipo = tipo
    this.protocolo = protocolo
  }

  /** Primeiro código de erro de negócio (ex.: LANCAMENTO_MANUAL_DIA_UTIL). */
  get codigo() {
    return this.erros[0]?.codigo ?? null
  }

  /** Mensagens de validação indexadas pelo campo do payload. */
  get porCampo() {
    return Object.fromEntries(this.erros.filter((e) => e.campo).map((e) => [e.campo, e.mensagem]))
  }

  /** Vale a pena tentar de novo daqui a pouco, sem mudar nada. */
  get transitorio() {
    return ['REDE', 'TEMPO', 'INDISPONIVEL'].includes(this.tipo)
  }

  get cancelada() {
    return this.tipo === 'CANCELADA'
  }
}

/** O servidor está respondendo? As telas mostram um aviso único enquanto não estiver. */
export const conexao = reactive({ semServidor: false })

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '/api/v1',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

/**
 * Integração com a sessão, configurada no main.js (evita dependência circular com a store):
 * - obterToken: devolve o JWT atual (ou null)
 * - obterUsuarioVisto: login de outro usuário cujos dados estão sendo consultados (admin/coordenação), ou null
 * - aoNaoAutenticado(codigo, mensagem): chamado em 401 (sessão ausente/expirada, acesso desativado)
 * - aoPrecisarTrocarSenha: chamado em 403 TROCAR_SENHA (senha provisória)
 */
let autenticacao = {
  obterToken: () => null,
  obterUsuarioVisto: () => null,
  aoNaoAutenticado: () => {},
  aoPrecisarTrocarSenha: () => {},
}

/** Consultas que são sempre do próprio usuário (o sino, a sessão, o cadastro, a equipe, os logs). */
const SEMPRE_DO_PROPRIO = [/^\/auth\//, /^\/notificacoes/, /^\/usuarios/, /^\/integracoes/, /^\/presenca/, /^\/logs/, /^\/erros-de-tela/]

export function configurarAutenticacao(config) {
  autenticacao = { ...autenticacao, ...config }
}

export function tokenAtual() {
  return autenticacao.obterToken()
}

http.interceptors.request.use((config) => {
  const token = autenticacao.obterToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  // Admin e coordenação consultando outra pessoa: as leituras levam ?usuario=login (as alterações, nunca)
  const visto = autenticacao.obterUsuarioVisto()
  const metodo = (config.method ?? 'get').toLowerCase()
  if (visto && metodo === 'get' && !SEMPRE_DO_PROPRIO.some((r) => r.test(config.url ?? ''))) {
    config.params = { usuario: visto, ...config.params }
  }
  return config
})

/** Em downloads (responseType: 'blob') o corpo de erro também vem como Blob. */
async function lerEnvelope(dados) {
  if (typeof Blob !== 'undefined' && dados instanceof Blob) {
    try {
      return JSON.parse(await dados.text())
    } catch {
      return null
    }
  }
  return dados && typeof dados === 'object' ? dados : null
}

const AVISE = 'se continuar, avise o administrador'

/** Frase para quando o servidor respondeu com erro e não explicou (não deveria acontecer, mas não vira texto técnico). */
function mensagemPorStatus(status, protocolo) {
  const comProtocolo = protocolo ? ` e informe o protocolo ${protocolo}` : ''
  if (status === 401) return 'Sua sessão terminou. Entre de novo para continuar.'
  if (status === 403) return 'Você não tem permissão para fazer isso.'
  if (status === 404) return `O sistema não encontrou o que foi pedido. Recarregue a página; ${AVISE}.`
  if (status === 408 || status === 504) return 'O sistema demorou demais para responder. Tente de novo em instantes.'
  if (status === 413) return 'O arquivo enviado é grande demais.'
  if (status === 429) return 'Muitas tentativas em pouco tempo. Aguarde um instante e tente de novo.'
  if (status === 502 || status === 503) {
    return 'O sistema está indisponível no momento (pode estar sendo reiniciado). Tente de novo em instantes.'
  }
  if (status >= 500) {
    return `O sistema encontrou um problema e a operação não foi concluída. Tente de novo; ${AVISE}${comProtocolo}.`
  }
  return `Não foi possível concluir a operação. Tente de novo; ${AVISE}${comProtocolo}.`
}

function tipoPorStatus(status, codigo) {
  if (status === 401) return 'SESSAO'
  if ([408, 502, 503, 504].includes(status) || codigo === 'SISTEMA_OCUPADO') return 'INDISPONIVEL'
  if (status >= 500) return 'SERVIDOR'
  return 'NEGOCIO'
}

// Sucesso: devolve direto o conteúdo de "dados" (downloads devolvem a resposta inteira).
// Falha: converte para ApiError com uma frase que a pessoa entende.
http.interceptors.response.use(
  (resposta) => {
    conexao.semServidor = false
    if (resposta.config.responseType === 'blob') return resposta
    const corpo = resposta.data
    // um proxy no caminho (ou o servidor ainda subindo) pode devolver uma página no lugar dos dados
    if (typeof corpo === 'string' && corpo.trimStart().startsWith('<')) {
      return Promise.reject(new ApiError(
        'O sistema respondeu de um jeito inesperado. Recarregue a página; se continuar, avise o administrador.',
        { status: resposta.status, tipo: 'RESPOSTA' },
      ))
    }
    return corpo?.dados ?? null
  },
  async (erro) => {
    if (axios.isCancel(erro)) {
      return Promise.reject(new ApiError('Pedido cancelado.', { tipo: 'CANCELADA' }))
    }
    if (!erro.response) {
      const demorou = erro.code === 'ECONNABORTED' || erro.code === 'ETIMEDOUT'
      if (!demorou) conexao.semServidor = true
      return Promise.reject(new ApiError(
        demorou
          ? 'O sistema demorou demais para responder. Verifique sua conexão e tente de novo.'
          : 'Sem conexão com o sistema. Verifique sua rede (ou a VPN) e tente de novo em instantes.',
        { tipo: demorou ? 'TEMPO' : 'REDE' },
      ))
    }
    conexao.semServidor = false
    const envelope = await lerEnvelope(erro.response.data)
    const erros = Array.isArray(envelope?.erros) ? envelope.erros.filter((e) => e?.mensagem) : []
    const status = erro.response.status
    const protocolo = envelope?.protocolo ?? erro.response.headers?.['x-protocolo'] ?? null
    const codigo = erros[0]?.codigo ?? null
    if (status === 401 && !erro.config?.url?.endsWith('/auth/login')) {
      autenticacao.aoNaoAutenticado(codigo, erros[0]?.mensagem ?? null)
    }
    if (status === 403 && codigo === 'TROCAR_SENHA') {
      autenticacao.aoPrecisarTrocarSenha()
    }
    const mensagem = erros.map((e) => e.mensagem).join(' ') || mensagemPorStatus(status, protocolo)
    return Promise.reject(new ApiError(mensagem, { status, erros, protocolo, tipo: tipoPorStatus(status, codigo) }))
  },
)
