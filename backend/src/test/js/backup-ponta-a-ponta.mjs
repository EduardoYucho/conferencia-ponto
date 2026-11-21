// Teste de ponta a ponta dos Backups de clientes, contra o sistema rodando de verdade. Este script faz os dois papéis,
// com os MESMOS módulos JS do sistema:
//  - a página do cliente (empacotador, tar, SHA-256, envelope, zstd em wasm), que manda os pedaços;
//  - o navegador de quem recebe (receber/recebido, e o montador do worker), que grava numa pasta "deste computador",
//    junta o .tar.zst, confere cada arquivo e extrai.
// O sistema só repassa: o teste confere que nenhum pedaço do backup fica no disco dele.
//
//   node src/test/js/backup-ponta-a-ponta.mjs <porta> <porta-publica> <pasta-de-trabalho> <login> <senha> [pasta-estado]
//
// <porta>: a do sistema (tela e API, com login); <porta-publica>: backup-clientes.porta-publica (a do túnel).
// O login precisa ter backup.coletar e backup.apagar (o Administrador tem).

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const estaticos = path.resolve(aqui, '../../main/resources/backup/coleta');
const modulo = (p) => import(pathToFileURL(path.join(estaticos, p)).href);
const { blocos, PrecisaVoltar } = await modulo('nucleo/empacotador.js');
const { montarEnvelope } = await modulo('nucleo/envelope.js');
const { Sha256 } = await modulo('nucleo/sha256.js');
const { criarCompactador } = await modulo('nucleo/zstd-compactar.js');
const { criarDescompactador } = await modulo('nucleo/zstd-descompactar.js');
const { montar, extrair, nomeDaParte } = await modulo('nucleo/montador.js');
const { Module } = await modulo('vendor/zstd.js');
const { novaChave, importarChave, cifrar, decifrar, novoSegredo } = await modulo('nucleo/cifra.js');
/** O segredo de sessão desta "página" (prende a coleta a ela). */
const SESSAO = novoSegredo();

const [porta, portaPublica, trabalho, login, senha, pastaEstado] = process.argv.slice(2);
const SISTEMA = `http://127.0.0.1:${porta}`;
// a porta pública (ou o endereço do túnel inteiro, "https://...", para testar pelo Cloudflare)
const PUBLICO = portaPublica.startsWith('http') ? portaPublica.replace(/\/+$/, '') : `http://127.0.0.1:${portaPublica}`;
const TAMANHO_BLOCO = 1024 * 1024;

let falhas = 0;
function confere(condicao, mensagem) {
  if (condicao) console.log(`  ok  ${mensagem}`);
  else {
    falhas++;
    console.log(`  FALHOU  ${mensagem}`);
  }
}

const pronto = new Promise((r) => { Module.onRuntimeInitialized = r; });
Module.init(fs.readFileSync(path.join(estaticos, 'vendor/zstd.wasm')));
await pronto;
const compactar = criarCompactador(Module);
const descompactar = criarDescompactador(Module);

console.log('SHA-256');
{
  const dados = crypto.randomBytes(1_000_003);
  confere(new Sha256().atualizar(dados).hex() === crypto.createHash('sha256').update(dados).digest('hex'), 'igual ao do Node (1 MB)');
  const s = new Sha256().atualizar(dados.subarray(0, 512 * 100));
  confere(new Sha256(s.exportar()).atualizar(dados.subarray(512 * 100)).hex() === crypto.createHash('sha256').update(dados).digest('hex'), 'continua de um estado exportado');
}

// ------------------------------------------------------------------ pasta de exemplo (parecida com um sistema DataFlex)
const origem = path.join(trabalho, 'origem', 'sislojas3');
fs.rmSync(path.join(trabalho), { recursive: true, force: true });
fs.mkdirSync(path.join(origem, 'AppSrc'), { recursive: true });
fs.mkdirSync(path.join(origem, 'Data'), { recursive: true });
fs.mkdirSync(path.join(origem, 'DDSrc', 'vazia'), { recursive: true });
fs.mkdirSync(path.join(origem, 'Logs', 'um nome de pasta bem comprido para passar dos cem caracteres do tar', 'mais um nível'), { recursive: true });
for (let i = 0; i < 300; i++) {
  fs.writeFileSync(path.join(origem, 'AppSrc', `Tela${String(i).padStart(3, '0')}.src`), `// Tela ${i}\nObject oTela${i} is a dbView\n`.repeat(40 + (i % 50)));
}
function registros(n, semente) {
  const linhas = [];
  for (let i = 0; i < n; i++) {
    linhas.push(`${String(i + semente).padStart(8, '0')}CLIENTE ${String(i % 997).padEnd(40, ' ')}RUA ${String(i % 313).padEnd(60, ' ')}${String((i * 7) % 100000).padStart(10, '0')}`.padEnd(256, ' '));
  }
  return Buffer.from(linhas.join(''), 'latin1');
}
fs.writeFileSync(path.join(origem, 'Data', 'CLIENTES.DAT'), registros(14000, 0));
fs.writeFileSync(path.join(origem, 'Data', 'CLIENTES.K1'), crypto.randomBytes(700_000));
fs.writeFileSync(path.join(origem, 'Data', 'VENDAS.DAT'), registros(20000, 5));
fs.writeFileSync(path.join(origem, 'Data', 'VENDAS.K1'), crypto.randomBytes(1_300_000));
fs.writeFileSync(path.join(origem, 'Data', 'VAZIO.DAT'), Buffer.alloc(0));
fs.writeFileSync(path.join(origem, 'DDSrc', 'Relatório de Ações.dd'), 'Class cClientesDD is a DataDictionary\n'.repeat(200));
fs.writeFileSync(path.join(origem, 'Logs', 'um nome de pasta bem comprido para passar dos cem caracteres do tar', 'mais um nível', 'erro.log'), 'erro\n'.repeat(1000));
fs.writeFileSync(path.join(origem, 'config.ini'), '[sistema]\ncaminho=G:\\sislojas3\n');

function listar(raiz) {
  const nome = path.basename(raiz);
  const entradas = [{ caminho: nome, tipo: 'pasta', tamanho: 0, modificado: 0, topo: null }];
  const andar = (pasta, rel) => {
    for (const item of fs.readdirSync(pasta, { withFileTypes: true })) {
      const r = rel ? `${rel}/${item.name}` : item.name;
      const completo = path.join(pasta, item.name);
      if (item.isDirectory()) {
        entradas.push({ caminho: `${nome}/${r}`, tipo: 'pasta', tamanho: 0, modificado: 0 });
        andar(completo, r);
      } else {
        const st = fs.statSync(completo);
        entradas.push({
          caminho: `${nome}/${r}`, tipo: 'arquivo', tamanho: st.size, modificado: Math.floor(st.mtimeMs),
          abrir: async () => {
            const foto = fs.statSync(completo);
            return {
              tamanho: foto.size,
              modificado: Math.floor(foto.mtimeMs),
              ler: async (i, j) => {
                const agora = fs.statSync(completo);
                if (agora.size !== foto.size || Math.floor(agora.mtimeMs) !== Math.floor(foto.mtimeMs)) {
                  const e = new Error('mudou'); e.name = 'NotReadableError'; throw e;
                }
                const fd = fs.openSync(completo, 'r');
                try {
                  const buf = Buffer.alloc(Math.max(0, Math.min(j, foto.size) - i));
                  fs.readSync(fd, buf, 0, buf.length, i);
                  return new Uint8Array(buf.buffer, buf.byteOffset, buf.length);
                } finally { fs.closeSync(fd); }
              },
            };
          },
        });
      }
    }
  };
  andar(raiz, '');
  entradas.sort((a, b) => (a.caminho < b.caminho ? -1 : a.caminho > b.caminho ? 1 : 0));
  return entradas;
}

// ------------------------------------------------------------------ HTTP
let token = null;
{
  const r = await fetch(`${SISTEMA}/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ login, senha }) });
  token = (await r.json()).dados.token;
}
async function api(metodo, caminho, corpo) {
  const r = await fetch(`${SISTEMA}/api/v1/backup${caminho}`, {
    method: metodo, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  });
  const texto = await r.text();
  if (!r.ok) throw new Error(`${metodo} ${caminho}: ${r.status} ${texto}`);
  return texto ? JSON.parse(texto).dados : null;
}
async function publico(tk, metodo, caminho, corpo, binario = false, sessao = SESSAO) {
  const r = await fetch(`${PUBLICO}/c/${tk}/api/${caminho}`, {
    method: metodo,
    headers: { 'Content-Type': binario ? 'application/octet-stream' : 'application/json', 'X-Coleta-Sessao': sessao },
    body: corpo === undefined ? undefined : (binario ? corpo : JSON.stringify(corpo)),
  });
  const texto = await r.text();
  return { status: r.status, dados: texto ? JSON.parse(texto) : null };
}

// ------------------------------------------------------------------ o navegador de quem recebe (como recepcao.js)
/** Recebe até a coleta fechar; grava em <destino>/<nome>.partes, monta, confere e informa. */
async function receptor(id, destino, nome, chave) {
  const partes = path.join(destino, `${nome}.partes`);
  fs.mkdirSync(partes, { recursive: true });
  for (;;) {
    const atual = await api('GET', `/coletas/${id}`);
    if (atual.situacao === 'FECHANDO') break;
    if (!['AGUARDANDO', 'RECEBENDO'].includes(atual.situacao)) return atual;
    for (;;) {
      const r = await fetch(`${SISTEMA}/api/v1/backup/coletas/${id}/receber`, { headers: { Authorization: `Bearer ${token}` } });
      if (r.status !== 200) break;
      const bytes = new Uint8Array(await r.arrayBuffer());
      const seq = Number(r.headers.get('x-seq'));
      const sha = crypto.createHash('sha256').update(bytes).digest('hex');
      if (sha !== r.headers.get('x-sha256')) continue;
      const conteudo = await decifrar(chave, bytes, seq); // falha se adulterado ou de outra chave
      fs.writeFileSync(path.join(partes, nomeDaParte(seq)), conteudo);
      await api('POST', `/coletas/${id}/recebido`, { seq, sha256: sha });
    }
  }
  const final = await fetch(`${SISTEMA}/api/v1/backup/coletas/${id}/final`, { headers: { Authorization: `Bearer ${token}` } });
  fs.writeFileSync(path.join(partes, 'final.zst'), new Uint8Array(await final.arrayBuffer()));
  const coleta = await api('GET', `/coletas/${id}`);
  const arquivo = path.join(destino, `${nome}.tar.zst`);
  fs.writeFileSync(arquivo, Buffer.alloc(0));
  const resultado = await montar({
    quantidade: coleta.proximoSeq,
    lerParte: async (i) => new Uint8Array(fs.readFileSync(path.join(partes, nomeDaParte(i)))),
    lerFinal: async () => new Uint8Array(fs.readFileSync(path.join(partes, 'final.zst'))),
    escrever: async (b) => fs.appendFileSync(arquivo, b),
    descompactar,
  });
  fs.writeFileSync(path.join(destino, `${nome}.json`), JSON.stringify({ quadros: resultado.quadros }));
  fs.rmSync(partes, { recursive: true, force: true });
  const { quadros, ...conferencia } = resultado;
  return api('POST', `/coletas/${id}/resultado`, conferencia);
}

/** A página do cliente: lê, compacta e manda cada pedaço (cada PUT só volta quando quem recebe gravou). */
async function coletar(tk, geracao, entradas, info, chave, pararDepoisDe = Infinity) {
  let seq = info.proximoSeq;
  let enviados = 0;
  for await (const bloco of blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: info.cursor })) {
    const quadro = await cifrar(chave, compactar(bloco.bruto, 3), seq);
    const env = montarEnvelope({ seq, geracao, quadro, bruto: bloco.bruto.length, fim: bloco.fim, arquivos: bloco.arquivos, naoLidos: bloco.naoLidos });
    for (;;) {
      const r = await publico(tk, 'PUT', `partes/${seq}?geracao=${geracao}`, env, true);
      if (r.status === 200) break;
      if (r.status === 503) continue; // quem recebe ainda gravando / ausente: manda de novo
      throw new Error(`parte ${seq}: ${r.status} ${JSON.stringify(r.dados)}`);
    }
    seq++;
    enviados++;
    if (enviados >= pararDepoisDe) return { seq, parou: true };
  }
  return { seq, parou: false };
}

function shaArquivo(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}
function compararPastas(a, b) {
  const lista = (raiz) => {
    const saida = [];
    const andar = (p, rel) => {
      for (const item of fs.readdirSync(p, { withFileTypes: true })) {
        const r = rel ? `${rel}/${item.name}` : item.name;
        if (item.isDirectory()) { saida.push(`${r}/`); andar(path.join(p, item.name), r); } else saida.push(`${r} ${shaArquivo(path.join(p, item.name))}`);
      }
    };
    andar(raiz, '');
    return saida.sort();
  };
  const la = lista(a);
  const lb = lista(b);
  const iguais = la.length === lb.length && la.every((x, i) => x === lb[i]);
  if (!iguais) {
    console.log('    origem :', la.filter((x) => !lb.includes(x)).slice(0, 5));
    console.log('    extraído:', lb.filter((x) => !la.includes(x)).slice(0, 5));
  }
  return iguais;
}

async function extrairNode(destino, nome, alvo) {
  const { quadros } = JSON.parse(fs.readFileSync(path.join(destino, `${nome}.json`), 'utf8'));
  const dados = fs.readFileSync(path.join(destino, `${nome}.tar.zst`));
  const inicios = [];
  let o = 0;
  for (const q of quadros) { inicios.push(o); o += q; }
  return extrair({
    quantidade: quadros.length,
    lerQuadro: async (i) => new Uint8Array(dados.subarray(inicios[i], inicios[i] + quadros[i])),
    descompactar,
    criarPasta: async (partes) => fs.mkdirSync(path.join(alvo, ...partes), { recursive: true }),
    criarArquivo: async (partes) => {
      fs.mkdirSync(path.join(alvo, ...partes.slice(0, -1)), { recursive: true });
      const caminho = path.join(alvo, ...partes);
      fs.writeFileSync(caminho, Buffer.alloc(0));
      return { escrever: async (b) => fs.appendFileSync(caminho, b), fechar: async () => {} };
    },
  });
}

// ------------------------------------------------------------------ cenários
await api('POST', '/clientes', { nome: 'Loja Teste', pastaSistema: 'G:\\sislojas3' }).catch(() => {});
const meuComputador = path.join(trabalho, 'meu-computador');
fs.mkdirSync(meuComputador, { recursive: true });

console.log('Coleta inteira: do cliente para a pasta de quem recebe, passando pelo sistema');
{
  const c = await api('POST', '/coletas', { cliente: 'Loja Teste' });
  const textoDaChave = novaChave();
  const chave = await importarChave(textoDaChave);
  const entradas = listar(origem);
  const info = (await publico(c.token, 'GET', 'coleta')).dados;
  confere(info.cliente === 'Loja Teste' && info.receptorOnline === true, 'página vê o cliente e quem recebe com a tela aberta');
  const g = await publico(c.token, 'POST', 'geracao', { geracao: 'g1', raiz: 'sislojas3', pastas: ['AppSrc', 'Data', 'DDSrc', 'Logs'], totalArquivos: entradas.length, totalBytes: 1 });
  const recebendo = receptor(c.id, meuComputador, c.nome, chave);
  const r = await coletar(c.token, 'g1', entradas, g.dados, chave);
  const fim = await publico(c.token, 'POST', 'concluir', { geracao: 'g1', totalPartes: r.seq });
  confere(fim.status === 200 && fim.dados.situacao === 'FECHANDO', `todos os ${r.seq} pedaços gravados por quem recebe`);
  const conferida = await recebendo;
  confere(conferida.situacao === 'INTEGRA', `conferência no navegador de quem recebe: ${conferida.conferencia?.mensagem}`);
  confere(conferida.resumo.arquivos === entradas.filter((e) => e.tipo === 'arquivo').length, `manifesto com ${conferida.resumo.arquivos} arquivos`);
  const arquivo = path.join(meuComputador, `${c.nome}.tar.zst`);
  confere(fs.existsSync(arquivo) && !fs.existsSync(path.join(meuComputador, `${c.nome}.partes`)), 'o .tar.zst ficou na pasta de quem recebe, sem os pedaços');
  if (pastaEstado) {
    const doSistema = [];
    const andar = (p) => { for (const i of fs.readdirSync(p, { withFileTypes: true })) i.isDirectory() ? andar(path.join(p, i.name)) : doSistema.push(i.name); };
    andar(pastaEstado);
    confere(doSistema.every((n) => ['coleta.json', 'blocos.jsonl', 'clientes.json', 'cloudflared.pid'].includes(n)), `no sistema, só dados de controle (${[...new Set(doSistema)].join(', ')})`);
  }
  const ext = await extrairNode(meuComputador, c.nome, path.join(trabalho, 'extraido1'));
  confere(ext.arquivos > 300, `extração no navegador: ${ext.arquivos} arquivos`);
  confere(compararPastas(origem, path.join(trabalho, 'extraido1', 'sislojas3')), 'extraído idêntico à origem (nomes, pastas vazias e SHA-256 de cada arquivo)');
  if (process.platform === 'win32') {
    const destino = path.join(trabalho, 'tar-do-windows');
    fs.mkdirSync(destino);
    try {
      execFileSync('C:\\Windows\\System32\\tar.exe', ['-xf', arquivo, '-C', destino], { stdio: 'pipe' });
      confere(compararPastas(origem, path.join(destino, 'sislojas3')), 'o tar.exe do Windows extrai o .tar.zst idêntico à origem');
    } catch (e) {
      confere(false, `o tar.exe do Windows extrai o .tar.zst (${String(e.stderr || e.message).trim()})`);
    }
  }
  const depois = await publico(c.token, 'PUT', `partes/${r.seq}?geracao=g1`, new Uint8Array(10), true);
  confere(depois.status === 409 || depois.status === 404, 'não aceita pedaço depois de concluída');
}

console.log('Queda no meio de um arquivo grande que muda antes de retomar');
{
  const c = await api('POST', '/coletas', { cliente: 'Loja Teste' });
  const chave = await importarChave(novaChave());
  let entradas = listar(origem);
  let g = await publico(c.token, 'POST', 'geracao', { geracao: 'a1', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  let parada = 0;
  for await (const b of blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: null })) {
    parada++;
    if (b.fim.caminho && b.fim.caminho.endsWith('VENDAS.DAT') && b.fim.offset > 0) break;
  }
  const recebendo = receptor(c.id, meuComputador, c.nome, chave);
  await coletar(c.token, 'a1', entradas, g.dados, chave, parada);
  const meio = (await publico(c.token, 'GET', 'coleta')).dados;
  confere(meio.cursor && meio.cursor.caminho.endsWith('VENDAS.DAT') && meio.cursor.offset > 0, `o sistema guardou onde parou (offset ${meio.cursor && meio.cursor.offset})`);

  const vendas = path.join(origem, 'Data', 'VENDAS.DAT');
  const conteudo = fs.readFileSync(vendas);
  conteudo.write('ALTERADO DURANTE A PAUSA', 1000, 'latin1');
  fs.writeFileSync(vendas, conteudo);
  fs.utimesSync(vendas, new Date(), new Date(Date.now() + 5000));
  entradas = listar(origem);

  g = await publico(c.token, 'POST', 'geracao', { geracao: 'a2', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  let voltou = false;
  try {
    await coletar(c.token, 'a2', entradas, g.dados, chave, 1);
  } catch (e) {
    voltou = e instanceof PrecisaVoltar;
  }
  confere(voltou, 'a página percebe que o arquivo mudou e pede para voltar ao começo dele');
  g = await publico(c.token, 'POST', 'geracao', { geracao: 'a3', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1, voltarAoInicioDe: meio.cursor.caminho });
  confere(g.dados.cursor && g.dados.cursor.offset === 0 && g.dados.proximoSeq < parada, `o sistema voltou para antes de VENDAS.DAT (pedaço ${g.dados.proximoSeq} de ${parada})`);
  const antigo = await publico(c.token, 'PUT', `partes/${g.dados.proximoSeq}?geracao=a2`, new Uint8Array(10), true);
  confere(antigo.status === 400 || antigo.status === 409, 'pedaço de geração antiga é recusado');
  const umBloco = (await blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: g.dados.cursor }).next()).value;
  const quadro = await cifrar(chave, compactar(umBloco.bruto, 3), g.dados.proximoSeq);
  const env = montarEnvelope({ seq: g.dados.proximoSeq, geracao: 'a3', quadro, bruto: umBloco.bruto.length, fim: umBloco.fim, arquivos: umBloco.arquivos, naoLidos: umBloco.naoLidos });
  env[env.length - 5] ^= 0xff;
  const corrompido = await publico(c.token, 'PUT', `partes/${g.dados.proximoSeq}?geracao=a3`, env, true);
  confere(corrompido.status === 422, 'pedaço corrompido no caminho é recusado (422) para ser reenviado');

  const r2 = await coletar(c.token, 'a3', entradas, g.dados, chave);
  await publico(c.token, 'POST', 'concluir', { geracao: 'a3', totalPartes: r2.seq });
  const conferida = await recebendo;
  confere(conferida.situacao === 'INTEGRA', `conferência: ${conferida.conferencia?.mensagem}`);
  await extrairNode(meuComputador, c.nome, path.join(trabalho, 'extraido2'));
  confere(compararPastas(origem, path.join(trabalho, 'extraido2', 'sislojas3')), 'extraído idêntico à origem, já com a alteração de VENDAS.DAT');
}

console.log('Ataques');
{
  const c = await api('POST', '/coletas', { cliente: 'Loja Teste' });
  const chave = await importarChave(novaChave());
  const entradas = listar(origem);
  const g = await publico(c.token, 'POST', 'geracao', { geracao: 'x1', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  // alguém com o link vazado, em outro navegador
  const intruso = novoSegredo();
  const outra = await publico(c.token, 'POST', 'geracao', { geracao: 'x9', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 }, false, intruso);
  confere(outra.status === 409 && outra.dados.codigo === 'OUTRA_SESSAO', 'link vazado: outro navegador não consegue começar a mandar');
  const infoIntruso = (await publico(c.token, 'GET', 'coleta', undefined, false, intruso)).dados;
  confere(infoIntruso.emOutroComputador === true, 'a página do intruso fica sabendo que a coleta está em outro computador');
  const bloco = (await blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: null }).next()).value;
  const cifrado = await cifrar(chave, compactar(bloco.bruto, 3), 0);
  const env = montarEnvelope({ seq: 0, geracao: 'x1', quadro: cifrado, bruto: bloco.bruto.length, fim: bloco.fim, arquivos: bloco.arquivos, naoLidos: bloco.naoLidos });
  const doIntruso = await publico(c.token, 'PUT', 'partes/0?geracao=x1', env, true, intruso);
  confere(doIntruso.status === 409 && doIntruso.dados.codigo === 'OUTRA_SESSAO', 'pedaço do intruso recusado');
  // pedaço sem cifra
  const claro = compactar(bloco.bruto, 3);
  const cab = new TextEncoder().encode(JSON.stringify({ seq: 0, geracao: 'x1', cifrado: false, shaQuadro: crypto.createHash('sha256').update(claro).digest('hex'), bruto: 1, fim: bloco.fim, arquivos: [], naoLidos: [] }));
  const semCifra = new Uint8Array(8 + cab.length + claro.length);
  semCifra.set(new TextEncoder().encode('RBK1'));
  new DataView(semCifra.buffer).setUint32(4, cab.length);
  semCifra.set(cab, 8);
  semCifra.set(claro, 8 + cab.length);
  const rSem = await publico(c.token, 'PUT', 'partes/0?geracao=x1', semCifra, true);
  confere(rSem.status === 400 && rSem.dados.codigo === 'PEDACO_SEM_CIFRA', 'pedaço sem criptografia recusado');
  // pedaço cifrado com outra chave (como se alguém trocasse o conteúdo): quem recebe não confirma
  const outraChave = await importarChave(novaChave());
  const falso = await cifrar(outraChave, claro, 0);
  const envFalso = montarEnvelope({ seq: 0, geracao: 'x1', quadro: falso, bruto: bloco.bruto.length, fim: bloco.fim, arquivos: bloco.arquivos, naoLidos: bloco.naoLidos });
  const enviando = publico(c.token, 'PUT', 'partes/0?geracao=x1', envFalso, true);
  let recusou = false;
  try {
    await receptor(c.id, path.join(trabalho, 'nao-deve-gravar'), c.nome, chave);
  } catch (e) {
    recusou = true;
  }
  confere(recusou, 'quem recebe percebe o pedaço que não abre com a chave do link e não confirma');
  await api('POST', `/coletas/${c.id}/cancelar`);
  await enviando;
  // link inventado com corpo grande: recusado sem ler o corpo
  const grande = new Uint8Array(20 * 1024 * 1024);
  const t0 = Date.now();
  const rGrande = await publico('B'.repeat(43), 'PUT', 'partes/0?geracao=x', grande, true);
  confere(rGrande.status === 404, `envio com link inventado recusado (${Date.now() - t0} ms)`);
  const pagina = await fetch(`${PUBLICO}/c/${c.token}`);
  const csp = pagina.headers.get('content-security-policy') || '';
  confere(csp.includes("default-src 'none'") && csp.includes("frame-ancestors 'none'"), 'página com política de segurança (CSP)');
}

console.log('Segurança');
{
  confere((await fetch(`${PUBLICO}/api/v1/backup/coletas`)).status === 404, 'porta pública não expõe a API da tela');
  confere((await fetch(`${PUBLICO}/`)).status === 404, 'porta pública não serve o sistema (só /c/...)');
  confere((await fetch(`${PUBLICO}/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status === 404, 'porta pública não chega ao login');
  confere((await fetch(`${SISTEMA}/api/v1/backup/coletas`)).status === 401, 'API da tela exige login');
  confere((await fetch(`${SISTEMA}/api/v1/backup/coletas/x/receber`)).status === 401, 'receber pedaços exige login');
  confere((await fetch(`${PUBLICO}/c/${'A'.repeat(43)}/api/coleta`)).status === 404, 'token desconhecido: 404');
  const js = await fetch(`${PUBLICO}/c/_/montador-worker.js`);
  confere(js.status === 200 && (js.headers.get('content-type') || '').includes('javascript'), 'arquivos da página e do montador servidos');
  confere((await fetch(`${PUBLICO}/c/_/vendor/zstd.wasm`)).status === 200, 'wasm do zstd servido');
}

console.log('Bloqueio de quem testa links');
{
  let barrado = false;
  for (let i = 0; i < 25 && !barrado; i++) {
    const r = await fetch(`${PUBLICO}/c/${'Q'.repeat(42)}${String.fromCharCode(65 + i)}/api/coleta`);
    barrado = r.status === 429;
  }
  confere(barrado, 'depois de 20 links inexistentes, o endereço é barrado (429)');
}

console.log(falhas === 0 ? '\nTUDO CERTO' : `\n${falhas} FALHA(S)`);
process.exit(falhas === 0 ? 0 : 1);
