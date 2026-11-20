// No navegador de quem recebe: junta os pedaços gravados num .tar.zst só e confere cada arquivo pelo SHA-256 do
// manifesto (o último pedaço). Também extrai um backup já montado. Não depende de onde os bytes estão: quem chama
// passa como ler e gravar (no worker, pastas do computador; nos testes, arquivos do Node).

import { Sha256 } from './sha256.js';
import { LeitorDeTar } from './tar-leitor.js';

export const MANIFESTO = '_coleta/manifesto.json';

/** O arquivo de cada pedaço em <pasta>/<nome>.partes (o último é "final.zst"). */
export const nomeDaParte = (i) => `p${String(i).padStart(7, '0')}.zst`;
const LIMITE_LISTA = 200;

/**
 * Lê os pedaços na ordem (0..quantidade-1 e o final), grava cada um em {escrever} (o .tar.zst é a concatenação deles)
 * e, ao mesmo tempo, descompacta e confere. Devolve o resultado da conferência e o tamanho de cada quadro (para
 * extrair depois sem ler o arquivo inteiro).
 */
export async function montar({ quantidade, lerParte, lerFinal, escrever, descompactar, aoAvancar = () => {} }) {
  const leitor = new LeitorDeTar();
  const calculados = new Map();
  let manifestoBytes = null;
  let atual = null;
  let bytesConferidos = 0;
  let tamanhoArquivo = 0;
  const quadros = [];

  const processar = (eventos) => {
    for (const e of eventos) {
      if (e.tipo === 'inicio') {
        atual = { nome: e.nome, entrada: e.entrada, sha: e.entrada === 'arquivo' ? new Sha256() : null, tamanho: 0, partes: [] };
      } else if (e.tipo === 'dados') {
        if (atual.nome === MANIFESTO) atual.partes.push(e.bytes);
        else if (atual.sha) atual.sha.atualizar(e.bytes);
        atual.tamanho += e.bytes.length;
        bytesConferidos += e.bytes.length;
      } else if (e.tipo === 'fim') {
        if (atual.nome === MANIFESTO) {
          manifestoBytes = juntar(atual.partes);
        } else if (atual.entrada === 'arquivo') {
          calculados.set(atual.nome, { sha: atual.sha.hex(), tamanho: atual.tamanho });
        }
        atual = null;
      }
    }
  };

  for (let i = 0; i <= quantidade; i++) {
    const quadro = i < quantidade ? await lerParte(i) : await lerFinal();
    await escrever(quadro);
    quadros.push(quadro.length);
    tamanhoArquivo += quadro.length;
    processar(leitor.alimentar(descompactar(quadro)));
    aoAvancar({ parte: i, de: quantidade + 1, bytesConferidos, tamanhoArquivo });
  }

  if (!manifestoBytes) {
    return resultado(false, 0, bytesConferidos, tamanhoArquivo, [], [], [], 'O backup não tem o manifesto no fim: está incompleto.', quadros, 0, 0, 0);
  }
  const manifesto = JSON.parse(new TextDecoder().decode(manifestoBytes));
  const divergentes = [];
  const faltando = [];
  let conferidos = 0;
  let nDiv = 0;
  let nFalt = 0;
  for (const item of manifesto.arquivos || []) {
    if ((item.tipo || 'arquivo') !== 'arquivo') continue;
    const calc = calculados.get(item.caminho);
    calculados.delete(item.caminho);
    if (!calc) {
      nFalt++;
      if (faltando.length < LIMITE_LISTA) faltando.push(item.caminho);
    } else if (calc.sha !== String(item.sha256).toLowerCase() || calc.tamanho !== item.tamanho) {
      nDiv++;
      if (divergentes.length < LIMITE_LISTA) divergentes.push(item.caminho);
    } else {
      conferidos++;
    }
  }
  const sobrando = [...calculados.keys()].sort();
  const nSobr = sobrando.length;
  const integra = nDiv === 0 && nFalt === 0 && nSobr === 0;
  const mensagem = integra
    ? `Todos os ${conferidos} arquivos idênticos aos lidos no cliente.`
    : `Diferenças: ${nDiv} divergente(s), ${nFalt} faltando, ${nSobr} a mais.`;
  return resultado(integra, conferidos, bytesConferidos, tamanhoArquivo, divergentes, faltando, sobrando.slice(0, LIMITE_LISTA), mensagem, quadros);
}

function resultado(integra, arquivosConferidos, bytesConferidos, tamanhoArquivo, divergentes, faltando, sobrando, mensagem, quadros) {
  return { integra, arquivosConferidos, bytesConferidos, tamanhoArquivo, divergentes, faltando, sobrando, mensagem, quadros };
}

function juntar(partes) {
  const total = partes.reduce((s, p) => s + p.length, 0);
  const saida = new Uint8Array(total);
  let o = 0;
  for (const p of partes) {
    saida.set(p, o);
    o += p.length;
  }
  return saida;
}

/** Nome que dá para criar no Windows (sem "..", letra de unidade, caracteres proibidos ou nome reservado). */
export function partesSeguras(caminho) {
  if (!caminho || caminho.startsWith('/') || caminho.startsWith('\\')) return null;
  const partes = caminho.split('/').filter((p) => p !== '');
  for (const p of partes) {
    if (p === '.' || p === '..' || /[<>:"\\|?*\x00-\x1f]/.test(p) || /[. ]$/.test(p)) return null;
    if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i.test(p)) return null;
  }
  return partes.length ? partes : null;
}

/**
 * Extrai um backup montado: {lerQuadro(i)} devolve o quadro i (os tamanhos vêm do relatório). {criarPasta(partes)} e
 * {criarArquivo(partes)} → {escrever(bytes), fechar()} são de quem chama. Caminho perigoso é pulado e listado.
 */
export async function extrair({ quantidade, lerQuadro, descompactar, criarPasta, criarArquivo, aoAvancar = () => {} }) {
  const leitor = new LeitorDeTar();
  const ignorados = [];
  let arquivos = 0;
  let bytes = 0;
  let aberto = null;
  let pulando = false;
  for (let i = 0; i < quantidade; i++) {
    const eventos = leitor.alimentar(descompactar(await lerQuadro(i)));
    for (const e of eventos) {
      if (e.tipo === 'inicio') {
        const partes = partesSeguras(e.nome);
        pulando = !partes || e.entrada === 'outro';
        if (pulando) {
          if (ignorados.length < LIMITE_LISTA) ignorados.push(e.nome);
          continue;
        }
        if (e.entrada === 'pasta') await criarPasta(partes);
        else aberto = await criarArquivo(partes);
      } else if (e.tipo === 'dados') {
        if (!pulando && aberto) {
          await aberto.escrever(e.bytes);
          bytes += e.bytes.length;
        }
      } else if (e.tipo === 'fim') {
        if (aberto) {
          await aberto.fechar();
          aberto = null;
          arquivos++;
        }
        pulando = false;
      }
    }
    aoAvancar({ parte: i, de: quantidade, bytes, arquivos });
  }
  return { arquivos, bytes, ignorados };
}
