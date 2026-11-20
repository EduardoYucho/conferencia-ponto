/**
 * As pastas deste computador que recebem os backups, lembradas pelo navegador (IndexedDB): a escolhida na tela (padrão
 * para os próximos links, vazia até alguém escolher) e a de cada coleta recebida aqui. O navegador guarda a permissão
 * de acesso e, depois de fechado, pede para confirmar com um clique.
 *
 * O seletor de pastas só existe em endereço seguro (https ou localhost): pelo http da rede, o Chrome precisa liberar o
 * endereço do sistema (veja {@link seletorDisponivel}).
 */

const BANCO = 'conferencia-ponto-backups'
const PADRAO = 'destino-padrao'

function abrir() {
  return new Promise((resolver, rejeitar) => {
    const pedido = indexedDB.open(BANCO, 1)
    pedido.onupgradeneeded = () => {
      pedido.result.createObjectStore('pastas')
    }
    pedido.onsuccess = () => resolver(pedido.result)
    pedido.onerror = () => rejeitar(pedido.error)
  })
}

async function usar(modo, acao) {
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
export const gravarPastaDaColeta = (id, pasta, nome) => gravar(`coleta:${id}`, { pasta, nome })
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
