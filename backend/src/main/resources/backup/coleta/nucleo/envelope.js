// Um pedaço do envio: "RBK1", tamanho do cabeçalho (4 bytes, big-endian), cabeçalho JSON e o quadro zstd.

import { sha256Hex } from './sha256.js';

const MAGICO = new TextEncoder().encode('RBK1');

export function montarEnvelope({ seq, geracao, quadro, bruto, fim, arquivos, naoLidos }) {
  const cabecalho = { seq, geracao, shaQuadro: sha256Hex(quadro), bruto, fim, arquivos, naoLidos };
  const json = new TextEncoder().encode(JSON.stringify(cabecalho));
  const saida = new Uint8Array(8 + json.length + quadro.length);
  saida.set(MAGICO, 0);
  new DataView(saida.buffer).setUint32(4, json.length);
  saida.set(json, 8);
  saida.set(quadro, 8 + json.length);
  return saida;
}

/** Lê o cabeçalho de um envelope guardado (para retomar: saber o cursor do último bloco já gravado). */
export function lerCabecalho(envelope) {
  const tamanho = new DataView(envelope.buffer, envelope.byteOffset, envelope.byteLength).getUint32(4);
  return JSON.parse(new TextDecoder().decode(envelope.subarray(8, 8 + tamanho)));
}
