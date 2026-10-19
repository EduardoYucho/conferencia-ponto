import { reactive } from 'vue'
import { ApiError, http, tokenAtual } from '@/api/http'

/**
 * Tratamento de erros das telas.
 *
 * Regra: a pessoa nunca lê texto técnico. O que o servidor explica (ApiError) é mostrado como veio; qualquer
 * outra falha (um defeito da própria tela) vira uma frase única, e o detalhe técnico segue para o log do
 * servidor (POST /erros-de-tela), onde o administrador o encontra na pasta de logs do usuário.
 */

const GENERICA = 'Algo não funcionou como esperado nesta tela. Tente de novo; se continuar, recarregue a página.'

/** Avisos que não pertencem a uma tela: falha inesperada e sistema atualizado com a aba aberta. */
export const avisos = reactive({
  /** { mensagem, recarregar } */
  inesperado: null,
  /** O servidor foi atualizado e esta aba ainda tem a versão antiga das telas. */
  atualizado: false,
  /** Idem, e o sistema ainda está reiniciando: a aba recarrega sozinha quando ele responder. */
  reiniciando: false,
})

/**
 * Frase para mostrar a partir de um erro capturado num `catch`.
 * Use sempre no lugar de `e.message`: um defeito da tela não pode aparecer como "Cannot read properties of null".
 */
export function mensagemDe(erro, padrao = GENERICA) {
  if (erro instanceof ApiError) return erro.message
  relatar(erro, 'acao')
  return padrao
}

/** O pedido foi cancelado pela própria tela (não é erro para mostrar). */
export const foiCancelado = (erro) => erro instanceof ApiError && erro.cancelada

// ------------------------------------------------------------------ relato ao servidor

const jaRelatados = new Map()
let relatosNoMinuto = 0
setInterval(() => (relatosNoMinuto = 0), 60_000)

/** Manda o defeito para o log do servidor: sem repetir o mesmo erro e sem enxurrada. */
export function relatar(erro, origem = '') {
  try {
    console.error(`[tela${origem ? `:${origem}` : ''}]`, erro)
    if (!tokenAtual()) return
    const mensagem = String(erro?.message ?? erro ?? 'erro sem mensagem').slice(0, 500)
    const agora = Date.now()
    if (agora - (jaRelatados.get(mensagem) ?? 0) < 60_000 || relatosNoMinuto >= 5) return
    jaRelatados.set(mensagem, agora)
    relatosNoMinuto++
    http.post('/erros-de-tela', {
      mensagem,
      tela: `${window.location.pathname}${origem ? ` (${origem})` : ''}`.slice(0, 200),
      detalhe: String(erro?.stack ?? '').slice(0, 4000) || null,
    }).catch(() => {})
  } catch {
    // relatar um erro nunca pode causar outro
  }
}

// ------------------------------------------------------------------ versão antiga das telas na aba

const CHAVE_RECARGA = 'conferencia-ponto.recarga'
/** Uma recarga a menos de 30 s da anterior não é feita: a aba só avisa (sem laço de recargas). */
const INTERVALO_ENTRE_RECARGAS = 30_000
/** Enquanto o sistema reinicia (`ponto atualizar`), a aba espera ele responder antes de recarregar. */
const ESPERA_PELO_SERVIDOR = { tentativas: 40, intervalo: 3000 }

/** Quando o Vite avisou a última falha ao carregar um arquivo de tela (`vite:preloadError`). */
let ultimaFalhaDeCarga = 0
/** A recarga já decidida: { destino }. Enquanto existe, os erros que a aba antiga ainda causa não são relatados. */
let recarga = null

/**
 * O erro de quem tem a versão antiga das telas. Depois do `vite:preloadError` (que esta aba cancela para recarregar
 * em vez de quebrar), o Vue Router recebe um componente vazio e responde "Couldn't resolve component": é a mesma
 * falha, não um defeito da tela.
 */
const ehTelaAntiga = (erro) => {
  const mensagem = String(erro?.message ?? erro ?? '')
  return /dynamically imported module|Importing a module script failed|Failed to fetch dynamically|error loading dynamically|Unable to preload CSS/i.test(mensagem)
    || (/Couldn't resolve component/i.test(mensagem) && Date.now() - ultimaFalhaDeCarga < 15_000)
}

const espera = (ms) => new Promise((resolver) => setTimeout(resolver, ms))

/** O sistema responde? (durante o `ponto atualizar` ele fica alguns segundos fora) */
async function servidorResponde() {
  try {
    const resposta = await fetch(import.meta.env.BASE_URL || '/', { cache: 'no-store' })
    return resposta.ok
  } catch {
    return false
  }
}

/**
 * Depois de uma atualização do sistema, a aba aberta pede arquivos de tela que não existem mais. Recarrega a
 * página uma vez, indo para a tela que a pessoa pediu; se o sistema ainda está reiniciando, espera ele voltar (com
 * o aviso na tela). Se já recarregou há pouco e continua, só avisa.
 *
 * A recarga sai na volta seguinte do navegador: o aviso do Vite e o erro do Vue Router chegam um logo depois do
 * outro, e só o segundo sabe o destino.
 */
function recarregarUmaVez(destino) {
  if (recarga) {
    if (destino) recarga.destino = destino
    return
  }
  let ultima = 0
  try {
    ultima = Number(sessionStorage.getItem(CHAVE_RECARGA) ?? 0)
  } catch {
    avisos.atualizado = true // sem sessionStorage não dá para saber se já recarregou: não arrisca um laço
    return
  }
  if (Date.now() - ultima < INTERVALO_ENTRE_RECARGAS) {
    avisos.atualizado = true
    return
  }
  recarga = { destino }
  setTimeout(recarregarQuandoResponder, 0)
}

async function recarregarQuandoResponder() {
  for (let tentativa = 0; tentativa < ESPERA_PELO_SERVIDOR.tentativas; tentativa++) {
    if (await servidorResponde()) {
      try {
        sessionStorage.setItem(CHAVE_RECARGA, String(Date.now()))
      } catch {
        // já conferido em recarregarUmaVez
      }
      if (recarga.destino) window.location.assign(recarga.destino)
      else window.location.reload()
      return
    }
    avisos.reiniciando = true
    await espera(ESPERA_PELO_SERVIDOR.intervalo)
  }
  // o sistema não voltou: fica o aviso com o botão de recarregar
  avisos.reiniciando = false
  avisos.atualizado = true
  recarga = null
}

// ------------------------------------------------------------------ rede de segurança

function tratarNaoCapturado(erro, origem) {
  if (!erro) return
  if (recarga) return // a aba vai recarregar: o que ainda quebra é efeito da versão antiga das telas
  if (erro instanceof ApiError) {
    // falha de API que nenhuma tela tratou: a frase do servidor serve; sessão e cancelamento têm fluxo próprio
    if (erro.cancelada || erro.tipo === 'SESSAO' || erro.codigo === 'TROCAR_SENHA') return
    // sem servidor, o aviso de conexão já está na tela
    if (erro.tipo !== 'REDE') avisos.inesperado = { mensagem: erro.message, recarregar: false }
    return
  }
  if (ehTelaAntiga(erro)) {
    recarregarUmaVez()
    return
  }
  relatar(erro, origem)
  avisos.inesperado = { mensagem: GENERICA, recarregar: true }
}

/** Liga a rede de segurança: nada que quebre numa tela fica só no console do navegador. */
export function instalarTratamentoGlobal(app, router) {
  app.config.errorHandler = (erro, _instancia, info) => tratarNaoCapturado(erro, `vue ${info}`)

  window.addEventListener('unhandledrejection', (evento) => {
    tratarNaoCapturado(evento.reason, 'promessa')
    evento.preventDefault()
  })

  window.addEventListener('error', (evento) => {
    // falha ao carregar imagem/fonte (sem `error`) e o aviso inofensivo do ResizeObserver não são defeitos
    if (!evento.error || /ResizeObserver loop/i.test(evento.message ?? '')) return
    tratarNaoCapturado(evento.error, 'janela')
  })

  window.addEventListener('vite:preloadError', (evento) => {
    evento.preventDefault()
    ultimaFalhaDeCarga = Date.now()
    recarregarUmaVez()
  })

  router.onError((erro, destino) => {
    if (ehTelaAntiga(erro)) recarregarUmaVez(destino?.fullPath)
    else tratarNaoCapturado(erro, 'rota')
  })
}
