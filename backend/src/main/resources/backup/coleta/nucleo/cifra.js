// Criptografia de ponta a ponta dos pedaços: AES-256-GCM com a chave que só existe no link, depois do "#" (o
// navegador nunca manda essa parte do endereço para servidor nenhum). Assim nem o sistema por onde os pedaços passam
// nem o túnel (Cloudflare) conseguem ler o backup; e um pedaço adulterado no caminho não passa na etiqueta do GCM.
//
// Formato de um pedaço cifrado: 12 bytes do vetor de inicialização (aleatório) + o cifrado com a etiqueta de 16
// bytes. O número do pedaço entra como dado autenticado: um pedaço trocado de lugar também não passa.
// (A tela do sistema, que recebe, tem a mesma conta em frontend/src/modulos/backup/cifra.js.)

const PREFIXO_AAD = 'backup-clientes:';

function paraBase64Url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function deBase64Url(texto) {
  const s = atob(texto.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

/** Uma chave nova (32 bytes), em texto para ir no link. */
export function novaChave() {
  return paraBase64Url(crypto.getRandomValues(new Uint8Array(32)));
}

/** A chave do link ("#k=..."), ou null. */
export function chaveDoEndereco(hash) {
  const m = /(?:^#|&)k=([A-Za-z0-9_-]{43})(?:&|$)/.exec(hash || '');
  return m ? m[1] : null;
}

export async function importarChave(texto) {
  const bytes = deBase64Url(texto);
  if (bytes.length !== 32) throw new Error('chave do link inválida');
  return crypto.subtle.importKey('raw', bytes, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

export async function cifrar(chave, dados, seq) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const aad = new TextEncoder().encode(PREFIXO_AAD + seq);
  const cifrado = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad }, chave, dados));
  const saida = new Uint8Array(12 + cifrado.length);
  saida.set(iv, 0);
  saida.set(cifrado, 12);
  return saida;
}

/** Decifra e confere (falha se o pedaço foi adulterado, trocado de lugar ou a chave não é a do link). */
export async function decifrar(chave, pedaco, seq) {
  const aad = new TextEncoder().encode(PREFIXO_AAD + seq);
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: pedaco.subarray(0, 12), additionalData: aad }, chave, pedaco.subarray(12)));
}

/** O segredo de sessão desta página (32 bytes): prende a coleta a este navegador. */
export function novoSegredo() {
  return paraBase64Url(crypto.getRandomValues(new Uint8Array(32)));
}
