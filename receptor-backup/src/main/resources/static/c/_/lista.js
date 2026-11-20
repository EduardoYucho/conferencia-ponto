// Percorre a pasta escolhida (sem ler conteúdo): nome, tamanho e data de cada arquivo, agrupados pela pasta de
// primeiro nível (AppSrc, Data, DDSrc...), para a tela de escolha e para a leitura depois.

export const RAIZ_SOLTOS = '__arquivos-da-raiz__';

function abridor(handle) {
  return async () => {
    const arquivo = await handle.getFile();
    return {
      tamanho: arquivo.size,
      modificado: arquivo.lastModified,
      ler: async (inicio, fim) => new Uint8Array(await arquivo.slice(inicio, fim).arrayBuffer()),
    };
  };
}

/**
 * Devolve {raiz, entradas, naoLidos, grupos}. entradas: [{caminho, tipo, tamanho, modificado, topo, abrir}] em ordem.
 * grupos: [{topo, nome, tipo: 'pasta'|'soltos', arquivos, bytes}].
 */
export async function listar(pastaRaiz, aoAvancar = () => {}) {
  const raiz = pastaRaiz.name;
  const entradas = [{ caminho: raiz, tipo: 'pasta', tamanho: 0, modificado: 0, topo: null }];
  const naoLidos = [];
  const grupos = new Map();
  let arquivos = 0;
  let bytes = 0;

  const grupo = (topo, nome, tipo) => {
    if (!grupos.has(topo)) grupos.set(topo, { topo, nome, tipo, arquivos: 0, bytes: 0 });
    return grupos.get(topo);
  };

  const pilha = [{ pasta: pastaRaiz, relativo: '' }];
  while (pilha.length > 0) {
    const { pasta, relativo } = pilha.pop();
    const arquivosDaPasta = [];
    try {
      for await (const [nome, handle] of pasta.entries()) {
        const caminhoRel = relativo ? `${relativo}/${nome}` : nome;
        if (handle.kind === 'directory') {
          const topo = relativo ? relativo.split('/')[0] : nome;
          grupo(topo, topo, 'pasta');
          entradas.push({ caminho: `${raiz}/${caminhoRel}`, tipo: 'pasta', tamanho: 0, modificado: 0, topo });
          pilha.push({ pasta: handle, relativo: caminhoRel });
        } else {
          arquivosDaPasta.push({ nome, handle, caminhoRel });
        }
      }
    } catch (erro) {
      naoLidos.push({ caminho: `${raiz}/${relativo}`, motivo: `pasta não pôde ser listada (${erro.name || erro.message})` });
    }
    for (let i = 0; i < arquivosDaPasta.length; i += 32) {
      const lote = arquivosDaPasta.slice(i, i + 32);
      const fotos = await Promise.all(lote.map(async (item) => {
        try {
          const arquivo = await item.handle.getFile();
          return { item, tamanho: arquivo.size, modificado: arquivo.lastModified };
        } catch (erro) {
          return { item, erro };
        }
      }));
      for (const foto of fotos) {
        const { item } = foto;
        const topo = relativo ? relativo.split('/')[0] : RAIZ_SOLTOS;
        const g = grupo(topo, relativo ? topo : 'Arquivos soltos na pasta principal', relativo ? 'pasta' : 'soltos');
        if (foto.erro) {
          // não deu nem para ver o tamanho agora (travado?): entra mesmo assim, a leitura tenta de novo
          entradas.push({ caminho: `${raiz}/${item.caminhoRel}`, tipo: 'arquivo', tamanho: 0, modificado: 0, topo, abrir: abridor(item.handle) });
          g.arquivos++;
          continue;
        }
        entradas.push({ caminho: `${raiz}/${item.caminhoRel}`, tipo: 'arquivo', tamanho: foto.tamanho, modificado: foto.modificado, topo, abrir: abridor(item.handle) });
        g.arquivos++;
        g.bytes += foto.tamanho;
        arquivos++;
        bytes += foto.tamanho;
      }
      aoAvancar({ arquivos, bytes });
    }
  }
  entradas.sort((a, b) => (a.caminho < b.caminho ? -1 : a.caminho > b.caminho ? 1 : 0));
  const listaGrupos = [...grupos.values()].sort((a, b) => {
    if (a.tipo !== b.tipo) return a.tipo === 'soltos' ? 1 : -1;
    return a.nome.localeCompare(b.nome, 'pt-BR', { sensitivity: 'base' });
  });
  return { raiz, entradas, naoLidos, grupos: listaGrupos };
}

/** Só o que foi marcado (e a própria pasta principal). */
export function filtrar(entradas, selecionados) {
  const marcados = new Set(selecionados);
  return entradas.filter((e) => e.topo === null || marcados.has(e.topo));
}
