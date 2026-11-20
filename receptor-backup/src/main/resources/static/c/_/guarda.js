// Onde os pedaços já compactados esperam o envio.
//
// No disco (armazenamento privado do navegador, OPFS): a leitura das pastas termina em minutos mesmo com a internet
// lenta (a "foto" do sistema fica mais consistente) e, se a aba fechar, o envio continua dos pedaços guardados sem ler
// de novo. Usa no máximo metade do espaço que o navegador oferece, até 8 GB; se passar disso, a leitura espera o envio.
// Sem OPFS (ou sem espaço), guarda na memória, com um limite pequeno.
//
// Tudo é apagado ao terminar. A pasta escolhida também fica lembrada (IndexedDB) para continuar depois.

const LIMITE_MEMORIA = 96 * 1024 * 1024;
const LIMITE_DISCO = 8 * 1024 * 1024 * 1024;

class GuardaMemoria {
  constructor() {
    this.tipo = 'memoria';
    this.limite = LIMITE_MEMORIA;
    this.pedacos = new Map();
    this.bytesPendentes = 0;
    this.estadoAtual = null;
  }
  async gravar(seq, bytes) {
    this.pedacos.set(seq, bytes);
    this.bytesPendentes += bytes.length;
  }
  async ler(seq) {
    const bytes = this.pedacos.get(seq);
    if (!bytes) throw new Error(`pedaço ${seq} não está guardado`);
    return bytes;
  }
  async remover(seq) {
    const bytes = this.pedacos.get(seq);
    if (bytes) {
      this.bytesPendentes -= bytes.length;
      this.pedacos.delete(seq);
    }
  }
  tem(seq) {
    return this.pedacos.has(seq);
  }
  seqs() {
    return [...this.pedacos.keys()].sort((a, b) => a - b);
  }
  async estado() {
    return this.estadoAtual;
  }
  async salvarEstado(estado) {
    this.estadoAtual = estado;
  }
  async limpar() {
    this.pedacos.clear();
    this.bytesPendentes = 0;
    this.estadoAtual = null;
  }
}

class GuardaDisco {
  constructor(pasta, limite) {
    this.tipo = 'disco';
    this.pasta = pasta;
    this.limite = limite;
    this.tamanhos = new Map();
    this.bytesPendentes = 0;
  }
  async carregar() {
    for await (const [nome, handle] of this.pasta.entries()) {
      const m = /^p(\d+)\.bin$/.exec(nome);
      if (m && handle.kind === 'file') {
        const arquivo = await handle.getFile();
        this.tamanhos.set(Number(m[1]), arquivo.size);
        this.bytesPendentes += arquivo.size;
      }
    }
  }
  async gravar(seq, bytes) {
    const handle = await this.pasta.getFileHandle(`p${seq}.bin`, { create: true });
    const escrita = await handle.createWritable();
    await escrita.write(bytes);
    await escrita.close();
    const anterior = this.tamanhos.get(seq) || 0;
    this.tamanhos.set(seq, bytes.length);
    this.bytesPendentes += bytes.length - anterior;
  }
  async ler(seq) {
    const handle = await this.pasta.getFileHandle(`p${seq}.bin`);
    return new Uint8Array(await (await handle.getFile()).arrayBuffer());
  }
  async remover(seq) {
    if (!this.tamanhos.has(seq)) return;
    try {
      await this.pasta.removeEntry(`p${seq}.bin`);
    } catch (e) {
      /* já não existe */
    }
    this.bytesPendentes -= this.tamanhos.get(seq);
    this.tamanhos.delete(seq);
  }
  tem(seq) {
    return this.tamanhos.has(seq);
  }
  seqs() {
    return [...this.tamanhos.keys()].sort((a, b) => a - b);
  }
  async estado() {
    try {
      const handle = await this.pasta.getFileHandle('estado.json');
      return JSON.parse(await (await handle.getFile()).text());
    } catch (e) {
      return null;
    }
  }
  async salvarEstado(estado) {
    const handle = await this.pasta.getFileHandle('estado.json', { create: true });
    const escrita = await handle.createWritable();
    await escrita.write(JSON.stringify(estado));
    await escrita.close();
  }
  async limpar() {
    for (const seq of this.seqs()) await this.remover(seq);
    try {
      await this.pasta.removeEntry('estado.json');
    } catch (e) {
      /* nada */
    }
  }
}

/** Abre a guarda desta coleta: no disco se der, senão na memória. */
export async function abrirGuarda(chave) {
  try {
    if (!navigator.storage || !navigator.storage.getDirectory) throw new Error('sem OPFS');
    const raiz = await navigator.storage.getDirectory();
    const pasta = await raiz.getDirectoryHandle(`receptor-${chave}`, { create: true });
    const estimativa = navigator.storage.estimate ? await navigator.storage.estimate() : { quota: 0, usage: 0 };
    const livre = Math.max(0, (estimativa.quota || 0) - (estimativa.usage || 0));
    const limite = Math.min(LIMITE_DISCO, Math.floor(livre / 2));
    if (limite < 256 * 1024 * 1024) throw new Error('pouco espaço no navegador');
    const guarda = new GuardaDisco(pasta, limite);
    // teste de escrita (navegador antigo pode não ter createWritable no OPFS)
    const teste = await pasta.getFileHandle('teste.tmp', { create: true });
    const escrita = await teste.createWritable();
    await escrita.write(new Uint8Array([1]));
    await escrita.close();
    await pasta.removeEntry('teste.tmp');
    await guarda.carregar();
    return guarda;
  } catch (erro) {
    return new GuardaMemoria();
  }
}

/** Apaga a pasta da guarda no OPFS (ao terminar). */
export async function apagarGuarda(chave) {
  try {
    const raiz = await navigator.storage.getDirectory();
    await raiz.removeEntry(`receptor-${chave}`, { recursive: true });
  } catch (e) {
    /* nada */
  }
}

// ---------------------------------------------------------------- pasta escolhida (IndexedDB)

function banco() {
  return new Promise((resolver, rejeitar) => {
    const pedido = indexedDB.open('receptor-backup', 1);
    pedido.onupgradeneeded = () => pedido.result.createObjectStore('pastas');
    pedido.onsuccess = () => resolver(pedido.result);
    pedido.onerror = () => rejeitar(pedido.error);
  });
}

export async function lembrarPasta(chave, handle) {
  try {
    const db = await banco();
    await new Promise((resolver, rejeitar) => {
      const tx = db.transaction('pastas', 'readwrite');
      tx.objectStore('pastas').put(handle, chave);
      tx.oncomplete = resolver;
      tx.onerror = () => rejeitar(tx.error);
    });
    db.close();
  } catch (e) {
    /* sem IndexedDB: só não lembra */
  }
}

export async function pastaLembrada(chave) {
  try {
    const db = await banco();
    const handle = await new Promise((resolver, rejeitar) => {
      const tx = db.transaction('pastas', 'readonly');
      const pedido = tx.objectStore('pastas').get(chave);
      pedido.onsuccess = () => resolver(pedido.result || null);
      pedido.onerror = () => rejeitar(pedido.error);
    });
    db.close();
    return handle;
  } catch (e) {
    return null;
  }
}

export async function esquecerPasta(chave) {
  try {
    const db = await banco();
    await new Promise((resolver) => {
      const tx = db.transaction('pastas', 'readwrite');
      tx.objectStore('pastas').delete(chave);
      tx.oncomplete = resolver;
      tx.onerror = resolver;
    });
    db.close();
  } catch (e) {
    /* nada */
  }
}
