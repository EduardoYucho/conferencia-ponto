// No navegador de quem recebe (a tela Backups de clientes do sistema), fora da tela para não travá-la:
//  - montar: junta os pedaços gravados em <pasta>/<nome>.partes num <nome>.tar.zst, confere cada arquivo pelo SHA-256
//    do manifesto, grava o relatório <nome>.json e apaga os pedaços;
//  - extrair: descompacta um backup montado numa pasta escolhida.
// As pastas são do computador de quem recebe (escolhidas no seletor do navegador).
import { Module } from './vendor/zstd.js';
import { criarDescompactador } from './nucleo/zstd-descompactar.js';
import { montar, extrair, nomeDaParte } from './nucleo/montador.js';

const pronto = new Promise((resolver) => {
  Module.onRuntimeInitialized = resolver;
});
Module.init(new URL('./vendor/zstd.wasm', import.meta.url).href);

async function lerArquivo(pasta, nome) {
  const arquivo = await (await pasta.getFileHandle(nome)).getFile();
  return new Uint8Array(await arquivo.arrayBuffer());
}

async function gravarTexto(pasta, nome, texto) {
  const escrita = await (await pasta.getFileHandle(nome, { create: true })).createWritable();
  await escrita.write(texto);
  await escrita.close();
}

async function acaoMontar({ pasta, nome, quantidade, relatorio }) {
  const partes = await pasta.getDirectoryHandle(`${nome}.partes`);
  const escrita = await (await pasta.getFileHandle(`${nome}.tar.zst`, { create: true })).createWritable();
  let resultado;
  try {
    resultado = await montar({
      quantidade,
      lerParte: (i) => lerArquivo(partes, nomeDaParte(i)),
      lerFinal: () => lerArquivo(partes, 'final.zst'),
      escrever: (bytes) => escrita.write(bytes),
      descompactar: criarDescompactador(Module),
      aoAvancar: (p) => self.postMessage({ progresso: p }),
    });
    await escrita.close();
  } catch (erro) {
    try {
      await escrita.abort();
    } catch (e) {
      /* nada */
    }
    throw erro;
  }
  await gravarTexto(pasta, `${nome}.json`, JSON.stringify({ ...relatorio, arquivo: `${nome}.tar.zst`, conferencia: { ...resultado, quadros: undefined }, quadros: resultado.quadros }, null, 2));
  try {
    await pasta.removeEntry(`${nome}.partes`, { recursive: true });
  } catch (e) {
    /* fica para apagar à mão */
  }
  return resultado;
}

async function acaoExtrair({ pasta, nome, destino }) {
  const relatorio = JSON.parse(new TextDecoder().decode(await lerArquivo(pasta, `${nome}.json`)));
  const quadros = relatorio.quadros || [];
  if (!quadros.length) throw new Error(`O relatório ${nome}.json não traz o índice do arquivo.`);
  const arquivo = await (await pasta.getFileHandle(`${nome}.tar.zst`)).getFile();
  const inicios = [];
  let o = 0;
  for (const q of quadros) {
    inicios.push(o);
    o += q;
  }
  const pastaDe = async (partes) => {
    let atual = destino;
    for (const p of partes) atual = await atual.getDirectoryHandle(p, { create: true });
    return atual;
  };
  return extrair({
    quantidade: quadros.length,
    lerQuadro: async (i) => new Uint8Array(await arquivo.slice(inicios[i], inicios[i] + quadros[i]).arrayBuffer()),
    descompactar: criarDescompactador(Module),
    criarPasta: (partes) => pastaDe(partes),
    criarArquivo: async (partes) => {
      const dir = await pastaDe(partes.slice(0, -1));
      const escrita = await (await dir.getFileHandle(partes[partes.length - 1], { create: true })).createWritable();
      return { escrever: (b) => escrita.write(b), fechar: () => escrita.close() };
    },
    aoAvancar: (p) => self.postMessage({ progresso: p }),
  });
}

self.onmessage = async (evento) => {
  const pedido = evento.data;
  try {
    await pronto;
    const resultado = pedido.acao === 'extrair' ? await acaoExtrair(pedido) : await acaoMontar(pedido);
    self.postMessage({ fim: true, resultado });
  } catch (erro) {
    self.postMessage({ fim: true, erro: `${erro.name || 'Erro'}: ${erro.message || erro}` });
  }
};
