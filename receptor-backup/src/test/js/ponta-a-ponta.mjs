// Teste de ponta a ponta: este script faz o papel do navegador, usando os MESMOS módulos da página (empacotador,
// tar, SHA-256, envelope e o zstd em wasm), contra um Receptor rodando de verdade.
//
//   node src/test/js/ponta-a-ponta.mjs <porta-admin> <porta-publica> <pasta-de-trabalho>
//
// Cenários: coleta inteira; coleta interrompida no meio de um arquivo grande que muda antes de retomar (volta ao
// começo dele); pedaço corrompido (recusado com 422); extração e comparação byte a byte com a origem.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const estaticos = path.resolve(aqui, '../../main/resources/static/c/_');
const { blocos, PrecisaVoltar } = await import(pathToUrl(path.join(estaticos, 'nucleo/empacotador.js')));
const { montarEnvelope } = await import(pathToUrl(path.join(estaticos, 'nucleo/envelope.js')));
const { Sha256 } = await import(pathToUrl(path.join(estaticos, 'nucleo/sha256.js')));
const { criarCompactador } = await import(pathToUrl(path.join(estaticos, 'nucleo/zstd-compactar.js')));
const { Module } = await import(pathToUrl(path.join(estaticos, 'vendor/zstd.js')));

function pathToUrl(p) {
  return new URL(`file:///${p.replace(/\\/g, '/')}`).href;
}

const [portaAdmin, portaPublica, trabalho] = process.argv.slice(2);
const ADMIN = `http://127.0.0.1:${portaAdmin}`;
const PUBLICO = `http://127.0.0.1:${portaPublica}`;

let falhas = 0;
function confere(condicao, mensagem) {
  if (condicao) console.log(`  ok  ${mensagem}`);
  else {
    falhas++;
    console.log(`  FALHOU  ${mensagem}`);
  }
}

// ------------------------------------------------------------------ zstd wasm no Node
const pronto = new Promise((r) => { Module.onRuntimeInitialized = r; });
Module.init(fs.readFileSync(path.join(estaticos, 'vendor/zstd.wasm')));
await pronto;
const compactar = criarCompactador(Module);

// ------------------------------------------------------------------ SHA-256 contra o do Node
{
  console.log('SHA-256');
  const dados = crypto.randomBytes(1_000_003);
  confere(new Sha256().atualizar(dados).hex() === crypto.createHash('sha256').update(dados).digest('hex'), 'igual ao do Node (1 MB)');
  const s = new Sha256().atualizar(dados.subarray(0, 512 * 100));
  const continua = new Sha256(s.exportar()).atualizar(dados.subarray(512 * 100));
  confere(continua.hex() === crypto.createHash('sha256').update(dados).digest('hex'), 'continua de um estado exportado');
  confere(new Sha256().hex() === crypto.createHash('sha256').update(Buffer.alloc(0)).digest('hex'), 'vazio');
}

// ------------------------------------------------------------------ pasta de exemplo (parecida com um sistema DataFlex)
const origem = path.join(trabalho, 'origem', 'sislojas3');
fs.rmSync(path.join(trabalho, 'origem'), { recursive: true, force: true });
fs.mkdirSync(path.join(origem, 'AppSrc'), { recursive: true });
fs.mkdirSync(path.join(origem, 'Data'), { recursive: true });
fs.mkdirSync(path.join(origem, 'DDSrc', 'vazia'), { recursive: true });
fs.mkdirSync(path.join(origem, 'Logs', 'um nome de pasta bem comprido para passar dos cem caracteres do tar', 'mais um nível'), { recursive: true });
for (let i = 0; i < 300; i++) {
  fs.writeFileSync(path.join(origem, 'AppSrc', `Tela${String(i).padStart(3, '0')}.src`),
    `// Tela ${i}\nObject oTela${i} is a dbView\n  Set Label to "Tela ${i}"\n`.repeat(40 + (i % 50)));
}
function registros(n, semente) {
  // registros de tamanho fixo, cheios de espaço (como .DAT do DataFlex)
  const linhas = [];
  for (let i = 0; i < n; i++) {
    linhas.push(`${String(i + semente).padStart(8, '0')}CLIENTE ${String(i % 997).padEnd(40, ' ')}RUA ${String(i % 313).padEnd(60, ' ')}${String((i * 7) % 100000).padStart(10, '0')}`.padEnd(256, ' '));
  }
  return Buffer.from(linhas.join(''), 'latin1');
}
fs.writeFileSync(path.join(origem, 'Data', 'CLIENTES.DAT'), registros(14000, 0)); // ~3,5 MB
fs.writeFileSync(path.join(origem, 'Data', 'CLIENTES.K1'), crypto.randomBytes(700_000));
fs.writeFileSync(path.join(origem, 'Data', 'CLIENTES.TAG'), 'CODIGO\r\nNOME\r\nENDERECO\r\n');
fs.writeFileSync(path.join(origem, 'Data', 'VENDAS.DAT'), registros(20000, 5)); // ~5 MB: arquivo "grande" com bloco de 1 MB
fs.writeFileSync(path.join(origem, 'Data', 'VENDAS.K1'), crypto.randomBytes(1_300_000));
fs.writeFileSync(path.join(origem, 'Data', 'VAZIO.DAT'), Buffer.alloc(0));
fs.writeFileSync(path.join(origem, 'DDSrc', 'Relatório de Ações.dd'), 'Class cClientesDD is a DataDictionary\n'.repeat(200));
fs.writeFileSync(path.join(origem, 'Logs', 'um nome de pasta bem comprido para passar dos cem caracteres do tar', 'mais um nível', 'erro-2026-11-20.log'), 'erro\n'.repeat(1000));
fs.writeFileSync(path.join(origem, 'config.ini'), '[sistema]\ncaminho=G:\\sislojas3\n');

// ------------------------------------------------------------------ listagem no Node (no papel da do navegador)
function listar(raiz) {
  const nome = path.basename(raiz);
  const entradas = [{ caminho: nome, tipo: 'pasta', tamanho: 0, modificado: 0, topo: null }];
  const andar = (pasta, rel) => {
    for (const item of fs.readdirSync(pasta, { withFileTypes: true })) {
      const r = rel ? `${rel}/${item.name}` : item.name;
      const completo = path.join(pasta, item.name);
      const topo = rel ? rel.split('/')[0] : (item.isDirectory() ? item.name : '__arquivos-da-raiz__');
      if (item.isDirectory()) {
        entradas.push({ caminho: `${nome}/${r}`, tipo: 'pasta', tamanho: 0, modificado: 0, topo });
        andar(completo, r);
      } else {
        const st = fs.statSync(completo);
        entradas.push({
          caminho: `${nome}/${r}`, tipo: 'arquivo', tamanho: st.size, modificado: Math.floor(st.mtimeMs), topo,
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
async function admin(metodo, caminho, corpo) {
  const r = await fetch(`${ADMIN}${caminho}`, {
    method: metodo, headers: { 'Content-Type': 'application/json', 'X-Receptor': '1' },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  });
  const texto = await r.text();
  if (!r.ok) throw new Error(`${metodo} ${caminho}: ${r.status} ${texto}`);
  return texto ? JSON.parse(texto) : null;
}
async function publico(token, metodo, caminho, corpo, binario = false) {
  const r = await fetch(`${PUBLICO}/c/${token}/api/${caminho}`, {
    method: metodo,
    headers: { 'Content-Type': binario ? 'application/octet-stream' : 'application/json' },
    body: corpo === undefined ? undefined : (binario ? corpo : JSON.stringify(corpo)),
  });
  const texto = await r.text();
  return { status: r.status, dados: texto ? JSON.parse(texto) : null };
}

const TAMANHO_BLOCO = 1024 * 1024;

/** Lê/compacta/envia a partir do cursor; para depois de {pararDepoisDe} pedaços (simula a aba fechada). */
async function coletar(token, geracao, entradas, info, pararDepoisDe = Infinity) {
  let seq = info.proximoSeq;
  let enviados = 0;
  for await (const bloco of blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: info.cursor })) {
    const quadro = compactar(bloco.bruto, 3);
    const env = montarEnvelope({ seq, geracao, quadro, bruto: bloco.bruto.length, fim: bloco.fim, arquivos: bloco.arquivos, naoLidos: bloco.naoLidos });
    const r = await publico(token, 'PUT', `partes/${seq}?geracao=${geracao}`, env, true);
    if (r.status !== 200) throw new Error(`parte ${seq}: ${r.status} ${JSON.stringify(r.dados)}`);
    seq++;
    enviados++;
    if (enviados >= pararDepoisDe) return { seq, parou: true };
  }
  return { seq, parou: false };
}

async function esperarConferencia(id) {
  for (let i = 0; i < 120; i++) {
    const c = await admin('GET', `/api/coletas/${id}`);
    if (c.situacao === 'INTEGRA' || c.situacao === 'DIVERGENTE') return c;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('conferência não terminou');
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
        if (item.isDirectory()) { saida.push(`${r}/`); andar(path.join(p, item.name), r); }
        else saida.push(`${r} ${shaArquivo(path.join(p, item.name))}`);
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

// ------------------------------------------------------------------ cenários
await admin('POST', '/api/clientes', { nome: 'Loja Teste', pastaSistema: 'G:\\sislojas3' }).catch(() => {});

console.log('Coleta inteira');
{
  const c = await admin('POST', '/api/coletas', { cliente: 'Loja Teste' });
  const entradas = listar(origem);
  const totalBytes = entradas.reduce((s, e) => s + (e.tamanho || 0), 0);
  const geracao = 'g1';
  const info = (await publico(c.link ? c.link.split('/c/')[1] : c.linkLocal.split('/c/')[1], 'GET', 'coleta')).dados;
  const token = c.linkLocal.split('/c/')[1];
  confere(info.cliente === 'Loja Teste' && info.pastaSistema === 'G:\\sislojas3', 'página recebe cliente e pasta');
  const g = await publico(token, 'POST', 'geracao', { geracao, raiz: 'sislojas3', pastas: ['AppSrc', 'Data', 'DDSrc', 'Logs', '__arquivos-da-raiz__'], totalArquivos: entradas.length, totalBytes });
  confere(g.status === 200 && g.dados.proximoSeq === 0, 'geração registrada');
  const r = await coletar(token, geracao, entradas, g.dados);
  const fim = await publico(token, 'POST', 'concluir', { geracao, totalPartes: r.seq });
  confere(fim.status === 200, `concluída com ${r.seq} pedaços`);
  const conferida = await esperarConferencia(c.id);
  confere(conferida.situacao === 'INTEGRA', `conferência: ${conferida.conferencia && conferida.conferencia.mensagem}`);
  confere(conferida.resumo.arquivos === entradas.filter((e) => e.tipo === 'arquivo').length, `manifesto com ${conferida.resumo.arquivos} arquivos`);
  console.log(`    ${totalBytes} bytes originais -> ${conferida.tamanhoArquivo} bytes (${(totalBytes / conferida.tamanhoArquivo).toFixed(1)}x)`);

  const depois = await publico(token, 'PUT', `partes/${r.seq}?geracao=${geracao}`, new Uint8Array(10), true);
  confere(depois.status === 409, 'não aceita pedaço depois de concluída');

  await admin('POST', `/api/coletas/${c.id}/extrair`, { destino: path.join(trabalho, 'extraido1') });
  let ext;
  for (let i = 0; i < 120; i++) {
    ext = await admin('GET', `/api/coletas/${c.id}`);
    if (ext.extracao && ext.extracao.situacao !== 'EXTRAINDO') break;
    await new Promise((res) => setTimeout(res, 250));
  }
  confere(ext.extracao.situacao === 'EXTRAIDO', `extração: ${ext.extracao.mensagem}`);
  const extraido = path.join(trabalho, 'extraido1', 'sislojas3');
  confere(compararPastas(origem, extraido), 'extraído idêntico à origem (nomes, pastas vazias e SHA-256 de cada arquivo)');
  confere(fs.existsSync(path.join(trabalho, 'extraido1', '_coleta', 'manifesto.json')), 'manifesto extraído junto');
}

console.log('Coleta interrompida no meio de um arquivo grande, que muda antes de retomar');
{
  const c = await admin('POST', '/api/coletas', { cliente: 'Loja Teste' });
  const token = c.linkLocal.split('/c/')[1];
  let entradas = listar(origem);
  let g = await publico(token, 'POST', 'geracao', { geracao: 'a1', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  // para no meio de Data/VENDAS.DAT (5 MB em blocos de 1 MB)
  let parada = null;
  for (let n = 1; n < 40; n++) {
    // descobre quantos pedaços até cair no meio de VENDAS.DAT
    let seq = 0;
    for await (const b of blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: null })) {
      seq++;
      if (b.fim.caminho && b.fim.caminho.endsWith('VENDAS.DAT') && b.fim.offset > 0) { parada = seq; break; }
    }
    break;
  }
  confere(parada != null, `há um ponto de parada no meio de VENDAS.DAT (pedaço ${parada})`);
  const r1 = await coletar(token, 'a1', entradas, g.dados, parada);
  const meio = (await publico(token, 'GET', 'coleta')).dados;
  confere(meio.cursor && meio.cursor.caminho.endsWith('VENDAS.DAT') && meio.cursor.offset > 0, `Receptor guardou o cursor no meio (offset ${meio.cursor && meio.cursor.offset})`);

  // "aba fechada"; enquanto isso o sistema grava em VENDAS.DAT
  const vendas = path.join(origem, 'Data', 'VENDAS.DAT');
  const conteudo = fs.readFileSync(vendas);
  conteudo.write('ALTERADO DURANTE A PAUSA', 1000, 'latin1');
  fs.writeFileSync(vendas, conteudo);
  fs.utimesSync(vendas, new Date(), new Date(Date.now() + 5000));
  entradas = listar(origem);

  // retomada: geração nova a partir do cursor do Receptor
  g = await publico(token, 'POST', 'geracao', { geracao: 'a2', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  let voltou = false;
  try {
    await coletar(token, 'a2', entradas, g.dados, 1);
  } catch (e) {
    voltou = e instanceof PrecisaVoltar;
  }
  confere(voltou, 'a página percebe que o arquivo mudou e pede para voltar ao começo dele');
  g = await publico(token, 'POST', 'geracao', { geracao: 'a3', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1, voltarAoInicioDe: meio.cursor.caminho });
  confere(g.dados.cursor && g.dados.cursor.caminho === meio.cursor.caminho && g.dados.cursor.offset === 0 && g.dados.proximoSeq < r1.seq,
    `Receptor voltou para antes de VENDAS.DAT (pedaço ${g.dados.proximoSeq} de ${r1.seq})`);
  const antigo = await publico(token, 'PUT', `partes/${g.dados.proximoSeq}?geracao=a2`, new Uint8Array(10), true);
  confere(antigo.status === 400 || antigo.status === 409, 'pedaço de geração antiga é recusado');

  // pedaço corrompido
  const umBloco = (await blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: g.dados.cursor }).next()).value;
  const quadro = compactar(umBloco.bruto, 3);
  const env = montarEnvelope({ seq: g.dados.proximoSeq, geracao: 'a3', quadro, bruto: umBloco.bruto.length, fim: umBloco.fim, arquivos: umBloco.arquivos, naoLidos: umBloco.naoLidos });
  env[env.length - 5] ^= 0xff;
  const corrompido = await publico(token, 'PUT', `partes/${g.dados.proximoSeq}?geracao=a3`, env, true);
  confere(corrompido.status === 422, 'pedaço corrompido no caminho é recusado (422) para ser reenviado');

  const r2 = await coletar(token, 'a3', entradas, g.dados);
  const fim = await publico(token, 'POST', 'concluir', { geracao: 'a3', totalPartes: r2.seq });
  confere(fim.status === 200, 'concluída depois da retomada');
  const conferida = await esperarConferencia(c.id);
  confere(conferida.situacao === 'INTEGRA', `conferência: ${conferida.conferencia && conferida.conferencia.mensagem}`);
  await admin('POST', `/api/coletas/${c.id}/extrair`, { destino: path.join(trabalho, 'extraido2') });
  let ext;
  for (let i = 0; i < 120; i++) {
    ext = await admin('GET', `/api/coletas/${c.id}`);
    if (ext.extracao && ext.extracao.situacao !== 'EXTRAINDO') break;
    await new Promise((res) => setTimeout(res, 250));
  }
  confere(compararPastas(origem, path.join(trabalho, 'extraido2', 'sislojas3')), 'extraído idêntico à origem, já com a alteração de VENDAS.DAT');
}

console.log('Coleta interrompida no meio de um arquivo grande que NÃO muda (continua do meio, com o SHA-256 salvo)');
{
  const c = await admin('POST', '/api/coletas', { cliente: 'Loja Teste' });
  const token = c.linkLocal.split('/c/')[1];
  const entradas = listar(origem);
  let g = await publico(token, 'POST', 'geracao', { geracao: 'b1', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  let parada = 0;
  for await (const b of blocos({ entradas, tamanhoBloco: TAMANHO_BLOCO, inicio: null })) {
    parada++;
    if (b.fim.caminho && b.fim.caminho.endsWith('VENDAS.DAT') && b.fim.offset > TAMANHO_BLOCO) break;
  }
  await coletar(token, 'b1', entradas, g.dados, parada);
  g = await publico(token, 'POST', 'geracao', { geracao: 'b2', raiz: 'sislojas3', pastas: [], totalArquivos: 1, totalBytes: 1 });
  confere(g.dados.cursor.offset > TAMANHO_BLOCO && g.dados.cursor.sha && g.dados.cursor.sha.n === g.dados.cursor.offset,
    `continua do meio de VENDAS.DAT (offset ${g.dados.cursor.offset}, SHA-256 salvo)`);
  const r = await coletar(token, 'b2', entradas, g.dados);
  await publico(token, 'POST', 'concluir', { geracao: 'b2', totalPartes: r.seq });
  const conferida = await esperarConferencia(c.id);
  confere(conferida.situacao === 'INTEGRA', `conferência: ${conferida.conferencia && conferida.conferencia.mensagem}`);
  globalThis.arquivoParaTarDoWindows = conferida.arquivo;
}

console.log('Segurança');
{
  const r = await fetch(`${PUBLICO}/api/coletas`);
  confere(r.status === 404, 'porta pública não expõe a API da tela');
  const r2 = await fetch(`${ADMIN}/api/coletas`, { headers: { 'Cf-Connecting-Ip': '1.2.3.4' } });
  confere(r2.status === 403, 'tela recusa o que vem pelo túnel');
  const r3 = await fetch(`${ADMIN}/api/clientes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"nome":"x"}' });
  confere(r3.status === 403, 'tela recusa alteração sem o cabeçalho X-Receptor');
  const r4 = await fetch(`${PUBLICO}/c/${'A'.repeat(43)}/api/coleta`);
  confere(r4.status === 404, 'token desconhecido: 404');
  const r5 = await fetch(`${PUBLICO}/c/_/coleta.js`);
  confere(r5.status === 200 && (r5.headers.get('content-type') || '').includes('javascript'), 'arquivos da página servidos');
  const r6 = await fetch(`${PUBLICO}/c/_/vendor/zstd.wasm`);
  confere(r6.status === 200, 'wasm do zstd servido');
}

if (process.platform === 'win32' && globalThis.arquivoParaTarDoWindows) {
  console.log('tar do Windows');
  const { execFileSync } = await import('node:child_process');
  const destino = path.join(trabalho, 'tar-do-windows');
  fs.rmSync(destino, { recursive: true, force: true });
  fs.mkdirSync(destino);
  try {
    execFileSync('C:\\Windows\\System32\\tar.exe', ['-xf', globalThis.arquivoParaTarDoWindows, '-C', destino], { stdio: 'pipe' });
    confere(compararPastas(origem, path.join(destino, 'sislojas3')), 'o tar.exe do Windows extrai o .tar.zst idêntico à origem');
  } catch (e) {
    confere(false, `o tar.exe do Windows extrai o .tar.zst (${String(e.stderr || e.message).trim()})`);
  }
}

console.log(falhas === 0 ? '\nTUDO CERTO' : `\n${falhas} FALHA(S)`);
process.exit(falhas === 0 ? 0 : 1);
