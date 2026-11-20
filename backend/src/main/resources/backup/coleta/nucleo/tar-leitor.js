// Leitura de um .tar em pedaços (ustar, com cabeçalhos PAX de caminho e tamanho), sem precisar dele inteiro na
// memória: cada pedaço alimentado devolve os eventos que completou, na ordem:
//   { tipo: 'inicio', nome, entrada: 'arquivo' | 'pasta' | 'outro', tamanho, modificado }
//   { tipo: 'dados', bytes }        (cópia: pode ser guardada)
//   { tipo: 'fim' }
// Depois do bloco de zeros que marca o fim do .tar, o resto é ignorado.

const decodificador = new TextDecoder();

function texto(bloco, inicio, tamanho) {
  let fim = inicio;
  while (fim < inicio + tamanho && bloco[fim] !== 0) fim++;
  return decodificador.decode(bloco.subarray(inicio, fim));
}

function octal(bloco, inicio, tamanho) {
  if (bloco[inicio] & 0x80) {
    // base 256 (tamanhos grandes no formato GNU)
    let valor = 0;
    for (let i = inicio + 1; i < inicio + tamanho; i++) valor = valor * 256 + bloco[i];
    return valor;
  }
  const t = texto(bloco, inicio, tamanho).trim();
  return t ? parseInt(t, 8) : 0;
}

function preenchimento(tamanho) {
  const resto = tamanho % 512;
  return resto === 0 ? 0 : 512 - resto;
}

export class LeitorDeTar {
  constructor() {
    this.estado = 'cabecalho';
    this.cabecalho = new Uint8Array(512);
    this.noCabecalho = 0;
    this.restante = 0;
    this.pular = 0;
    this.pax = null;
    this.paxDados = null;
    this.paxLido = 0;
    this.terminou = false;
  }

  alimentar(pedaco) {
    const eventos = [];
    let i = 0;
    while (i < pedaco.length && !this.terminou) {
      if (this.estado === 'pular') {
        const n = Math.min(this.pular, pedaco.length - i);
        this.pular -= n;
        i += n;
        if (this.pular === 0) this.estado = 'cabecalho';
        continue;
      }
      if (this.estado === 'cabecalho') {
        const n = Math.min(512 - this.noCabecalho, pedaco.length - i);
        this.cabecalho.set(pedaco.subarray(i, i + n), this.noCabecalho);
        this.noCabecalho += n;
        i += n;
        if (this.noCabecalho < 512) continue;
        this.noCabecalho = 0;
        this._lerCabecalho(eventos);
        continue;
      }
      if (this.estado === 'pax') {
        const n = Math.min(this.restante, pedaco.length - i);
        this.paxDados.set(pedaco.subarray(i, i + n), this.paxLido);
        this.paxLido += n;
        this.restante -= n;
        i += n;
        if (this.restante === 0) {
          if (this.tipoPax === 'x') this.pax = this._lerPax(this.paxDados);
          this._depoisDosDados(this.paxDados.length);
        }
        continue;
      }
      if (this.estado === 'dados') {
        const n = Math.min(this.restante, pedaco.length - i);
        eventos.push({ tipo: 'dados', bytes: pedaco.slice(i, i + n) });
        this.restante -= n;
        i += n;
        if (this.restante === 0) {
          eventos.push({ tipo: 'fim' });
          this._depoisDosDados(this.tamanhoAtual);
        }
      }
    }
    return eventos;
  }

  _depoisDosDados(tamanho) {
    this.pular = preenchimento(tamanho);
    this.estado = this.pular > 0 ? 'pular' : 'cabecalho';
  }

  _lerCabecalho(eventos) {
    const b = this.cabecalho;
    if (b.every((x) => x === 0)) {
      this.terminou = true;
      return;
    }
    const tipo = String.fromCharCode(b[156] || 48);
    const tamanho = octal(b, 124, 12);
    if (tipo === 'x' || tipo === 'g') {
      // 'x' vale para a próxima entrada; 'g' (global) é lido e ignorado
      this.tipoPax = tipo;
      this.estado = 'pax';
      this.restante = tamanho;
      this.paxDados = new Uint8Array(tamanho);
      this.paxLido = 0;
      if (tamanho === 0) this.estado = 'cabecalho';
      return;
    }
    const nome = texto(b, 0, 100);
    const prefixo = texto(b, 263, 6) === 'ustar' ? texto(b, 345, 155) : '';
    const pax = this.pax || {};
    this.pax = null;
    const caminho = pax.path ?? (prefixo ? `${prefixo}/${nome}` : nome);
    const tamanhoReal = pax.size != null ? Number(pax.size) : tamanho;
    const entrada = tipo === '5' ? 'pasta' : tipo === '0' || tipo === '\0' || tipo === '7' ? 'arquivo' : 'outro';
    const modificado = octal(b, 136, 12) * 1000;
    eventos.push({ tipo: 'inicio', nome: caminho, entrada, tamanho: entrada === 'pasta' ? 0 : tamanhoReal, modificado });
    const dados = entrada === 'pasta' ? 0 : tamanhoReal;
    if (dados > 0) {
      this.estado = 'dados';
      this.restante = dados;
      this.tamanhoAtual = dados;
    } else {
      eventos.push({ tipo: 'fim' });
      this.estado = 'cabecalho';
    }
  }

  _lerPax(dados) {
    const registros = {};
    let i = 0;
    while (i < dados.length) {
      let espaco = i;
      while (espaco < dados.length && dados[espaco] !== 0x20) espaco++;
      const tamanho = parseInt(decodificador.decode(dados.subarray(i, espaco)), 10);
      if (!tamanho) break;
      const linha = decodificador.decode(dados.subarray(espaco + 1, i + tamanho - 1));
      const igual = linha.indexOf('=');
      if (igual > 0) registros[linha.slice(0, igual)] = linha.slice(igual + 1);
      i += tamanho;
    }
    return registros;
  }
}
