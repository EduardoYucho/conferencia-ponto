import axios from 'axios'

/**
 * Erro normalizado a partir do envelope padrão da API:
 * { sucesso: false, dados: null, erros: [{ codigo, mensagem, campo }], timestamp }
 */
export class ApiError extends Error {
  constructor(mensagem, { status = null, erros = [] } = {}) {
    super(mensagem)
    this.name = 'ApiError'
    this.status = status
    this.erros = erros
  }

  /** Primeiro código de erro de negócio (ex.: LANCAMENTO_MANUAL_DIA_UTIL). */
  get codigo() {
    return this.erros[0]?.codigo ?? null
  }

  /** Mensagens de validação indexadas pelo campo do payload. */
  get porCampo() {
    return Object.fromEntries(this.erros.filter((e) => e.campo).map((e) => [e.campo, e.mensagem]))
  }
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '/api/v1',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

/**
 * Integração com a sessão, configurada no main.js (evita dependência circular com a store):
 * - obterToken: devolve o JWT atual (ou null)
 * - obterUsuarioVisto: login de outro usuário cujos dados estão sendo consultados (admin/coordenação), ou null
 * - aoNaoAutenticado: chamado em 401 (sessão ausente/expirada)
 * - aoPrecisarTrocarSenha: chamado em 403 TROCAR_SENHA (senha provisória)
 */
let autenticacao = {
  obterToken: () => null,
  obterUsuarioVisto: () => null,
  aoNaoAutenticado: () => {},
  aoPrecisarTrocarSenha: () => {},
}

/** Consultas que são sempre do próprio usuário (o sino, a sessão, o cadastro). */
const SEMPRE_DO_PROPRIO = [/^\/auth\//, /^\/notificacoes/, /^\/usuarios/, /^\/integracoes/]

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
  return dados
}

// Sucesso: devolve direto o conteúdo de "dados" (downloads devolvem a resposta inteira).
// Falha: converte para ApiError com as mensagens do back-end.
http.interceptors.response.use(
  (resposta) => (resposta.config.responseType === 'blob' ? resposta : (resposta.data?.dados ?? null)),
  async (erro) => {
    const envelope = await lerEnvelope(erro.response?.data)
    const erros = Array.isArray(envelope?.erros) ? envelope.erros : []
    const status = erro.response?.status ?? null
    if (status === 401 && !erro.config?.url?.endsWith('/auth/login')) {
      autenticacao.aoNaoAutenticado()
    }
    if (status === 403 && erros[0]?.codigo === 'TROCAR_SENHA') {
      autenticacao.aoPrecisarTrocarSenha()
    }
    const mensagem =
      erros.map((e) => e.mensagem).join(' ') ||
      (erro.response
        ? `A API respondeu com erro ${status}.`
        : 'Não foi possível conectar à API. Verifique se o back-end está em execução.')
    return Promise.reject(new ApiError(mensagem, { status, erros }))
  },
)
