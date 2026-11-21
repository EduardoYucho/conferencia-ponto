/**
 * A mesma criptografia da página de coleta (backend/src/main/resources/backup/coleta/nucleo/cifra.js), do lado de quem
 * recebe: a chave nasce aqui ao gerar o link (vai no link, depois do "#", e fica guardada neste navegador) e cada
 * pedaço que chega é decifrado e conferido antes de ser gravado. Formato: 12 bytes do vetor de inicialização + cifrado
 * AES-256-GCM com etiqueta; o número do pedaço é dado autenticado.
 */

const PREFIXO_AAD = 'backup-clientes:'

function paraBase64Url(bytes) {
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function deBase64Url(texto) {
  const s = atob(texto.replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(s, (c) => c.charCodeAt(0))
}

/** Uma chave nova (32 bytes), em texto para ir no link. */
export function novaChave() {
  return paraBase64Url(crypto.getRandomValues(new Uint8Array(32)))
}

export async function importarChave(texto) {
  return crypto.subtle.importKey('raw', deBase64Url(texto), { name: 'AES-GCM' }, false, ['decrypt'])
}

/** Decifra e confere (falha se o pedaço foi adulterado, trocado de lugar ou a chave não é a do link). */
export async function decifrar(chave, pedaco, seq) {
  const aad = new TextEncoder().encode(PREFIXO_AAD + seq)
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: pedaco.subarray(0, 12), additionalData: aad }, chave, pedaco.subarray(12)))
}
