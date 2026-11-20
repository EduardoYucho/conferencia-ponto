// Descompacta um quadro zstd (um pedaço do backup), com o módulo wasm do zstd já iniciado. Cada pedaço é um quadro
// independente e traz o tamanho original no cabeçalho (ZSTD_compress grava), então dá para descompactar um por um.

export function criarDescompactador(Module) {
  return function descompactar(quadro) {
    const origem = Module._malloc(Math.max(1, quadro.byteLength));
    let destino = 0;
    try {
      Module.HEAPU8.set(quadro, origem);
      const tamanho = Module._ZSTD_getFrameContentSize(origem, quadro.byteLength);
      // -1 (desconhecido) e -2 (erro) chegam como inteiro com sinal; os quadros do backup sempre trazem o tamanho
      if (tamanho < 0) throw new Error('quadro zstd sem o tamanho original');
      destino = Module._malloc(Math.max(1, tamanho));
      const resultado = Module._ZSTD_decompress(destino, tamanho, origem, quadro.byteLength);
      if (Module._ZSTD_isError(resultado)) throw new Error(`zstd não descompactou (código ${resultado})`);
      return Module.HEAPU8.slice(destino, destino + resultado);
    } finally {
      Module._free(origem);
      if (destino) Module._free(destino);
    }
  };
}
