/**
 * As pastas deste computador que recebem os backups, lembradas pelo navegador (IndexedDB): a escolhida na tela (padrão
 * para os próximos links, vazia até alguém escolher) e a de cada coleta recebida aqui. O navegador guarda a permissão
 * de acesso e, depois de fechado, pede para confirmar com um clique.
 *
 * O seletor de pastas só existe em endereço seguro (https ou localhost): pelo http da rede, o Chrome precisa liberar o
 * endereço do sistema (veja {@link seletorDisponivel}).
 */

const BANCO = 'expedio-backups'
/** O nome de antes (o sistema se chamava Conferência de Ponto): as pastas guardadas nele passam para o novo. */
const BANCO_ANTIGO = 'conferencia-ponto-backups'
const PADRAO = 'destino-padrao'

function abrir(nome = BANCO) {
  return new Promise((resolver, rejeitar) => {
    const pedido = indexedDB.open(nome, 1)
    pedido.onupgradeneeded = () => {
      pedido.result.createObjectStore('pastas')
    }
    pedido.onsuccess = () => resolver(pedido.result)
    pedido.onerror = () => rejeitar(pedido.error)
  })
}

/** Traz as pastas lembradas com o nome antigo (uma vez; depois apaga o banco antigo). */
async function trazerDoNomeAntigo() {
  if (typeof indexedDB?.databases !== 'function') return
  const bancos = await indexedDB.databases()
  if (!bancos.some((b) => b.name === BANCO_ANTIGO)) return
  const antigo = await abrir(BANCO_ANTIGO)
  let itens
  try {
    itens = await new Promise((resolver, rejeitar) => {
      const tx = antigo.transaction('pastas', 'readonly')
      const chaves = tx.objectStore('pastas').getAllKeys()
      const valores = tx.objectStore('pastas').getAll()
      tx.oncomplete = () => resolver(chaves.result.map((chave, i) => [chave, valores.result[i]]))
      tx.onerror = () => rejeitar(tx.error)
    })
  } finally {
    antigo.close()
  }
  const novo = await abrir()
  try {
    await new Promise((resolver, rejeitar) => {
      const tx = novo.transaction('pastas', 'readwrite')
      const pastas = tx.objectStore('pastas')
      for (const [chave, valor] of itens) pastas.put(valor, chave)
      tx.oncomplete = resolver
      tx.onerror = () => rejeitar(tx.error)
    })
  } finally {
    novo.close()
  }
  indexedDB.deleteDatabase(BANCO_ANTIGO)
}

let migracao = null

async function usar(modo, acao) {
  // falha na migração não impede o uso: no pior caso, a pasta é escolhida de novo
  await (migracao ??= trazerDoNomeAntigo().catch(() => {}))
  const db = await abrir()
  try {
    return await new Promise((resolver, rejeitar) => {
      const tx = db.transaction('pastas', modo)
      const pedido = acao(tx.objectStore('pastas'))
      tx.oncomplete = () => resolver(pedido?.result)
      tx.onerror = () => rejeitar(tx.error)
    })
  } finally {
    db.close()
  }
}

const ler = (chave) => usar('readonly', (s) => s.get(chave)).catch(() => null)
const gravar = (chave, valor) => usar('readwrite', (s) => s.put(valor, chave))
const remover = (chave) => usar('readwrite', (s) => s.delete(chave)).catch(() => {})

/** O seletor de pastas existe aqui (Chrome/Edge em endereço seguro). */
export function seletorDisponivel() {
  return typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function' && window.isSecureContext
}

/** Abre o seletor de pastas DESTE computador (com permissão de gravar). Cancelar devolve null. */
export async function escolherPasta() {
  try {
    return await window.showDirectoryPicker({ id: 'backups-clientes', mode: 'readwrite' })
  } catch (erro) {
    if (erro?.name === 'AbortError') return null
    throw erro
  }
}

export const pastaPadrao = () => ler(PADRAO)
export const gravarPastaPadrao = (pasta) => gravar(PADRAO, pasta)
export const esquecerPastaPadrao = () => remover(PADRAO)

/** {pasta, nome} da coleta recebida neste navegador (ou null). */
export const pastaDaColeta = (id) => ler(`coleta:${id}`)
/** chave: a do link (decifra os pedaços); fica só neste navegador. */
export const gravarPastaDaColeta = (id, pasta, nome, chave) => gravar(`coleta:${id}`, { pasta, nome, chave })
export const esquecerPastaDaColeta = (id) => remover(`coleta:${id}`)

/** A permissão de gravar na pasta: 'granted', 'prompt' ou 'denied' (sem pedir). */
export async function permissao(pasta) {
  try {
    return await pasta.queryPermission({ mode: 'readwrite' })
  } catch {
    return 'denied'
  }
}

/** Pede a permissão (precisa vir de um clique). */
export async function pedirPermissao(pasta) {
  try {
    return (await pasta.requestPermission({ mode: 'readwrite' })) === 'granted'
  } catch {
    return false
  }
}
