// Monta o .tar em blocos de ~{tamanhoBloco} bytes, a partir da lista ordenada de entradas.
//
// Regras de corte (é o que permite retomar):
//  - arquivo pequeno (até metade do bloco) entra inteiro num bloco: é lido de uma vez, de uma "foto" consistente;
//  - antes de um arquivo grande, o bloco atual é fechado: sempre existe um ponto de retomada no começo dele;
//  - dentro de um arquivo grande, o bloco é fechado a cada {tamanhoBloco}; o cursor guarda onde parou e o estado do
//    SHA-256, para continuar dali se o arquivo não tiver mudado.
//
// Cada bloco sai com: os bytes do .tar, o cursor de onde a leitura parou ({fim}) e os arquivos que terminaram nele
// (caminho, tamanho, data, SHA-256, se mudou durante a leitura), que vão para o manifesto.

import { Sha256 } from './sha256.js';
import { cabecalho, preenchimento } from './tar.js';

export class PrecisaVoltar extends Error {
  constructor(caminho) {
    super(`O arquivo ${caminho} mudou desde a última leitura; ele será lido de novo inteiro.`);
    this.caminho = caminho;
  }
}

const TENTATIVAS = 6;
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

class Acumulador {
  constructor(capacidade) {
    this.dados = new Uint8Array(capacidade);
    this.tamanho = 0;
  }
  adicionar(bytes) {
    if (this.tamanho + bytes.length > this.dados.length) {
      const maior = new Uint8Array(Math.max(this.dados.length * 2, this.tamanho + bytes.length));
      maior.set(this.dados.subarray(0, this.tamanho));
      this.dados = maior;
    }
    this.dados.set(bytes, this.tamanho);
    this.tamanho += bytes.length;
  }
  zeros(n) {
    if (n > 0) this.adicionar(new Uint8Array(n));
  }
  retirar() {
    const saida = this.dados.slice(0, this.tamanho);
    this.tamanho = 0;
    return saida;
  }
}

/**
 * entradas: [{caminho, tipo: 'arquivo'|'pasta', tamanho, modificado, abrir: async () => foto}] em ordem.
 *   foto: {tamanho, modificado, ler: async (inicio, fim) => Uint8Array}; ler falha se o arquivo mudou depois da foto.
 * inicio: cursor de onde continuar (null = do começo).
 * naoLidosIniciais: o que não deu para listar (vai no primeiro bloco).
 * aoAvancar(info): chamado durante a leitura ({arquivoAtual, lidoBytes, lidoArquivos}).
 */
export async function* blocos({ entradas, tamanhoBloco, inicio = null, naoLidosIniciais = [], aoAvancar = () => {} }) {
  const metade = Math.floor(tamanhoBloco / 2);
  const buffer = new Acumulador(tamanhoBloco + metade + 4096);
  let arquivos = [];
  let naoLidos = inicio ? [] : [...naoLidosIniciais];
  let lidoBytes = 0;
  let lidoArquivos = 0;

  if (inicio && inicio.fimDaLista) return;

  let indice = 0;
  let retomada = null;
  if (inicio && inicio.caminho) {
    indice = entradas.findIndex((e) => e.caminho >= inicio.caminho);
    if (indice < 0) indice = entradas.length;
    if (inicio.offset > 0) {
      const entrada = entradas[indice];
      if (!entrada || entrada.caminho !== inicio.caminho || entrada.tipo !== 'arquivo') {
        throw new PrecisaVoltar(inicio.caminho);
      }
      retomada = inicio;
    }
    // o que veio antes do ponto de retomada já foi lido numa leitura anterior
    for (let i = 0; i < indice; i++) {
      if (entradas[i].tipo === 'arquivo') {
        lidoBytes += entradas[i].tamanho || 0;
        lidoArquivos++;
      }
    }
  }

  const proximoCursor = (i) => (i < entradas.length ? { caminho: entradas[i].caminho, offset: 0 } : { fimDaLista: true });
  const fechar = (fim) => {
    const bloco = { bruto: buffer.retirar(), fim, arquivos, naoLidos };
    arquivos = [];
    naoLidos = [];
    return bloco;
  };

  for (; indice < entradas.length; indice++) {
    const entrada = entradas[indice];
    aoAvancar({ arquivoAtual: entrada.caminho, lidoBytes, lidoArquivos });

    if (entrada.tipo === 'pasta') {
      buffer.adicionar(cabecalho(entrada.caminho, 'pasta', 0, entrada.modificado));
      arquivos.push({ caminho: entrada.caminho, tipo: 'pasta', modificado: entrada.modificado });
      if (buffer.tamanho >= tamanhoBloco) yield fechar(proximoCursor(indice + 1));
      continue;
    }

    // Abre a foto do arquivo (com novas tentativas: arquivo travado pelo sistema por um instante)
    let foto = null;
    let erro = null;
    for (let t = 0; t < TENTATIVAS && !foto; t++) {
      try {
        foto = await entrada.abrir();
      } catch (e) {
        erro = e;
        await espera(150 * (t + 1));
      }
    }
    if (!foto) {
      naoLidos.push({ caminho: entrada.caminho, motivo: motivoDe(erro) });
      if (retomada) throw new PrecisaVoltar(entrada.caminho);
      continue;
    }

    const grande = foto.tamanho > metade || retomada;
    if (!grande) {
      // Pequeno: lido de uma vez; se mudar durante a leitura, lê de novo (fica consistente)
      let dados = null;
      for (let t = 0; t < TENTATIVAS && !dados; t++) {
        try {
          if (t > 0) foto = await entrada.abrir();
          if (foto.tamanho > metade) break;
          dados = await foto.ler(0, foto.tamanho);
        } catch (e) {
          erro = e;
          await espera(150 * (t + 1));
        }
      }
      if (dados) {
        buffer.adicionar(cabecalho(entrada.caminho, 'arquivo', dados.length, foto.modificado));
        buffer.adicionar(dados);
        buffer.zeros(preenchimento(dados.length));
        const sha = new Sha256().atualizar(dados).hex();
        arquivos.push({ caminho: entrada.caminho, tipo: 'arquivo', tamanho: dados.length, modificado: foto.modificado, sha256: sha });
        lidoBytes += dados.length;
        lidoArquivos++;
        if (buffer.tamanho >= tamanhoBloco) yield fechar(proximoCursor(indice + 1));
        continue;
      }
      if (foto.tamanho <= metade) {
        naoLidos.push({ caminho: entrada.caminho, motivo: motivoDe(erro) });
        continue;
      }
      // cresceu e virou grande: segue como grande
    }

    // Grande: começa num bloco novo (ponto de retomada no começo dele)
    let offset = 0;
    let tamanho = foto.tamanho;
    let modificado = foto.modificado;
    let sha;
    let alterado = false;
    if (retomada) {
      if (foto.tamanho !== retomada.tamanho || foto.modificado !== retomada.modificado) {
        throw new PrecisaVoltar(entrada.caminho);
      }
      offset = retomada.offset;
      tamanho = retomada.tamanho;
      sha = new Sha256(retomada.sha);
      alterado = !!retomada.alterado;
      lidoBytes += offset;
      retomada = null;
    } else {
      if (buffer.tamanho > 0) yield fechar({ caminho: entrada.caminho, offset: 0 });
      buffer.adicionar(cabecalho(entrada.caminho, 'arquivo', tamanho, modificado));
      sha = new Sha256();
    }

    while (offset < tamanho) {
      const n = Math.min(tamanhoBloco - buffer.tamanho, tamanho - offset);
      let dados = null;
      for (let t = 0; t < TENTATIVAS && !dados; t++) {
        try {
          dados = await foto.ler(offset, offset + n);
          if (dados.length < n) {
            // o arquivo diminuiu depois da foto: completa com zeros (o tamanho já está no cabeçalho)
            const completo = new Uint8Array(n);
            completo.set(dados);
            dados = completo;
            alterado = true;
          }
        } catch (e) {
          erro = e;
          // mudou depois da foto: tira outra e continua do mesmo ponto (o arquivo fica marcado)
          alterado = true;
          await espera(100 * (t + 1));
          try {
            foto = await entrada.abrir();
            modificado = foto.modificado;
          } catch (e2) {
            erro = e2;
          }
        }
      }
      if (!dados) {
        // não deu para ler este trecho: completa com zeros para o .tar continuar válido, e avisa
        dados = new Uint8Array(n);
        alterado = true;
        if (!naoLidos.some((x) => x.caminho === entrada.caminho)) {
          naoLidos.push({ caminho: entrada.caminho, motivo: `trecho não lido (${motivoDe(erro)}); completado com zeros` });
        }
      }
      sha.atualizar(dados);
      buffer.adicionar(dados);
      offset += n;
      lidoBytes += n;
      aoAvancar({ arquivoAtual: entrada.caminho, lidoBytes, lidoArquivos });
      if (buffer.tamanho >= tamanhoBloco && offset < tamanho) {
        yield fechar({ caminho: entrada.caminho, offset, tamanho, modificado, sha: sha.exportar(), alterado });
      }
    }
    buffer.zeros(preenchimento(tamanho));
    const item = { caminho: entrada.caminho, tipo: 'arquivo', tamanho, modificado, sha256: sha.hex() };
    if (alterado) item.alterado = true;
    arquivos.push(item);
    lidoArquivos++;
    if (buffer.tamanho >= tamanhoBloco) yield fechar(proximoCursor(indice + 1));
  }

  if (buffer.tamanho > 0 || arquivos.length > 0 || naoLidos.length > 0) {
    yield fechar({ fimDaLista: true });
  }
  aoAvancar({ arquivoAtual: null, lidoBytes, lidoArquivos });
}

export function motivoDe(erro) {
  if (!erro) return 'não foi possível ler';
  const nome = erro.name || '';
  if (nome === 'NotReadableError') return 'em uso ou travado pelo sistema';
  if (nome === 'NotAllowedError' || nome === 'SecurityError') return 'sem permissão';
  if (nome === 'NotFoundError') return 'apagado durante a cópia';
  return erro.message || String(erro);
}
