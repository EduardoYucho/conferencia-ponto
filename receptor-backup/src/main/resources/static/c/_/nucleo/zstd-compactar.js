// Compacta um bloco num quadro zstd independente (ZSTD_compress), usando o módulo wasm do zstd já iniciado.
// Quadros independentes concatenados formam um .zst válido: é assim que os blocos viram um arquivo só no Receptor.

export function criarCompactador(Module) {
  return function compactar(dados, nivel) {
    const tamanho = dados.byteLength;
    const limite = Module._ZSTD_compressBound(tamanho);
    const destino = Module._malloc(limite);
    const origem = Module._malloc(Math.max(1, tamanho));
    try {
      Module.HEAPU8.set(dados, origem);
      const resultado = Module._ZSTD_compress(destino, limite, origem, tamanho, nivel);
      if (Module._ZSTD_isError(resultado)) throw new Error(`zstd falhou (código ${resultado})`);
      return Module.HEAPU8.slice(destino, destino + resultado);
    } finally {
      Module._free(destino);
      Module._free(origem);
    }
  };
}
