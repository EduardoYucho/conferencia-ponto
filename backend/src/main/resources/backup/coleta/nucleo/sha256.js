// SHA-256 incremental, com o estado exportável: a leitura de um arquivo grande pode parar no meio (página fechada)
// e continuar depois de onde parou sem reler o começo. O estado só é exportado em fronteira de 64 bytes, que é onde os
// blocos de leitura cortam (múltiplos de 512).

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const INICIAL = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];

export class Sha256 {
  constructor(estado) {
    this.h = new Uint32Array(estado ? estado.h : INICIAL);
    this.n = estado ? estado.n : 0; // bytes já processados
    this.pendente = new Uint8Array(64);
    this.nPendente = 0;
    this.w = new Uint32Array(64);
  }

  /** Estado para continuar depois (só em fronteira de 64 bytes). */
  exportar() {
    if (this.nPendente !== 0) throw new Error('SHA-256: estado fora da fronteira de 64 bytes');
    return { h: Array.from(this.h), n: this.n };
  }

  atualizar(dados) {
    let i = 0;
    const total = dados.length;
    this.n += total;
    if (this.nPendente > 0) {
      const falta = 64 - this.nPendente;
      const copia = Math.min(falta, total);
      this.pendente.set(dados.subarray(0, copia), this.nPendente);
      this.nPendente += copia;
      i = copia;
      if (this.nPendente === 64) {
        this._bloco(this.pendente, 0);
        this.nPendente = 0;
      }
    }
    for (; i + 64 <= total; i += 64) this._bloco(dados, i);
    if (i < total) {
      this.pendente.set(dados.subarray(i), 0);
      this.nPendente = total - i;
    }
    return this;
  }

  hex() {
    const bits = this.n * 8;
    const resto = this.nPendente;
    const tamanho = resto < 56 ? 64 : 128;
    const fim = new Uint8Array(tamanho);
    fim.set(this.pendente.subarray(0, resto));
    fim[resto] = 0x80;
    const alto = Math.floor(bits / 0x100000000);
    const baixo = bits >>> 0;
    const v = new DataView(fim.buffer);
    v.setUint32(tamanho - 8, alto);
    v.setUint32(tamanho - 4, baixo);
    const h = new Uint32Array(this.h);
    this._processar(h, fim, 0);
    if (tamanho === 128) this._processar(h, fim, 64);
    let saida = '';
    for (let j = 0; j < 8; j++) saida += h[j].toString(16).padStart(8, '0');
    return saida;
  }

  _bloco(dados, deslocamento) {
    this._processar(this.h, dados, deslocamento);
  }

  _processar(h, d, o) {
    const w = this.w;
    for (let t = 0; t < 16; t++) {
      const p = o + t * 4;
      w[t] = (d[p] << 24) | (d[p + 1] << 16) | (d[p + 2] << 8) | d[p + 3];
    }
    for (let t = 16; t < 64; t++) {
      const x = w[t - 15];
      const y = w[t - 2];
      const s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      const s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    let a = h[0], b = h[1], c = h[2], e = h[4], f = h[5], g = h[6], hh = h[7];
    let dd = h[3];
    for (let t = 0; t < 64; t++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K[t] + w[t]) | 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      hh = g; g = f; f = e; e = (dd + t1) | 0;
      dd = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + dd) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
  }
}

export function sha256Hex(dados) {
  return new Sha256().atualizar(dados).hex();
}
