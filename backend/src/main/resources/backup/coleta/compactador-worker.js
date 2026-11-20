// Um compactador zstd por worker: a página usa alguns em paralelo (metade dos núcleos do processador, até 4).
import { Module } from './vendor/zstd.js';
import { criarCompactador } from './nucleo/zstd-compactar.js';

const pronto = new Promise((resolver) => {
  Module.onRuntimeInitialized = resolver;
});
Module.init(new URL('./vendor/zstd.wasm', import.meta.url).href);

let compactar = null;

self.onmessage = async (evento) => {
  const { id, dados, nivel } = evento.data;
  try {
    if (!compactar) {
      await pronto;
      compactar = criarCompactador(Module);
    }
    const quadro = compactar(dados, nivel);
    self.postMessage({ id, quadro }, [quadro.buffer]);
  } catch (erro) {
    self.postMessage({ id, erro: String((erro && erro.message) || erro) });
  }
};

pronto.then(() => self.postMessage({ pronto: true }));
