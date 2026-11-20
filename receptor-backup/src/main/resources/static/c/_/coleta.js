// Página de coleta: escolhe a pasta do sistema, lê, compacta e envia ao Receptor, continuando de onde parou.
import { blocos, PrecisaVoltar } from './nucleo/empacotador.js';
import { montarEnvelope } from './nucleo/envelope.js';
import { sha256Hex } from './nucleo/sha256.js';
import { listar, filtrar } from './lista.js';
import { abrirGuarda, apagarGuarda, lembrarPasta, pastaLembrada, esquecerPasta } from './guarda.js';

const token = location.pathname.split('/')[2];
const chave = sha256Hex(new TextEncoder().encode(token)).slice(0, 24);
const $ = (id) => document.getElementById(id);

// ------------------------------------------------------------------ apoio

class ErroHttp extends Error {
  constructor(status, codigo, mensagem) {
    super(mensagem || `Erro ${status}`);
    this.status = status;
    this.codigo = codigo;
  }
}

const espera = (ms) => new Promise((r) => setTimeout(r, ms));

async function pedir(metodo, caminho, corpo, { tempoMs = 60000, binario = false } = {}) {
  const controle = new AbortController();
  const relogio = setTimeout(() => controle.abort(), tempoMs);
  try {
    const opcoes = { method: metodo, signal: controle.signal, cache: 'no-store', headers: {} };
    if (corpo !== undefined) {
      if (binario) {
        opcoes.body = corpo;
        opcoes.headers['Content-Type'] = 'application/octet-stream';
      } else {
        opcoes.body = JSON.stringify(corpo);
        opcoes.headers['Content-Type'] = 'application/json';
      }
    }
    const resposta = await fetch(`/c/${token}/api/${caminho}`, opcoes);
    const texto = await resposta.text();
    let dados = null;
    try {
      dados = texto ? JSON.parse(texto) : null;
    } catch (e) {
      dados = null;
    }
    if (!resposta.ok) {
      throw new ErroHttp(resposta.status, dados && dados.codigo, (dados && dados.mensagem) || `O Receptor respondeu ${resposta.status}.`);
    }
    return dados;
  } finally {
    clearTimeout(relogio);
  }
}

function bytes(n) {
  if (n == null || isNaN(n)) return '—';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toLocaleString('pt-BR', { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 })} ${u[i]}`;
}

function duracao(segundos) {
  if (segundos == null || !isFinite(segundos)) return '—';
  const s = Math.round(segundos);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${String(s % 60).padStart(2, '0')} s`;
  const h = Math.floor(m / 60);
  return `${h} h ${String(m % 60).padStart(2, '0')} min`;
}

function mostrar(passo) {
  for (const el of document.querySelectorAll('.passo')) el.hidden = el.id !== passo;
}

function erroNaTela(texto) {
  const caixa = $('erro');
  caixa.textContent = texto || '';
  caixa.hidden = !texto;
}

function novaGeracao() {
  const b = new Uint8Array(12);
  crypto.getRandomValues(b);
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

// ------------------------------------------------------------------ compactadores (workers)

class Compactadores {
  constructor(quantidade, nivel) {
    this.nivel = nivel;
    this.proximoId = 1;
    this.espera = new Map();
    this.livres = [];
    this.fila = [];
    this.workers = [];
    this.prontos = Promise.all(Array.from({ length: quantidade }, () => this._criar()));
  }
  _criar() {
    return new Promise((resolver, rejeitar) => {
      const worker = new Worker(new URL('./compactador-worker.js', import.meta.url), { type: 'module' });
      worker.onmessage = (ev) => {
        const m = ev.data;
        if (m.pronto) {
          this.workers.push(worker);
          this.livres.push(worker);
          this._despachar();
          resolver();
          return;
        }
        const tarefa = this.espera.get(m.id);
        this.espera.delete(m.id);
        this.livres.push(worker);
        this._despachar();
        if (m.erro) tarefa.rejeitar(new Error(m.erro));
        else tarefa.resolver(m.quadro);
      };
      worker.onerror = (ev) => rejeitar(new Error(`O compactador não iniciou: ${ev.message || 'erro no worker'}`));
    });
  }
  compactar(dados) {
    return new Promise((resolver, rejeitar) => {
      this.fila.push({ id: this.proximoId++, dados, resolver, rejeitar });
      this._despachar();
    });
  }
  _despachar() {
    while (this.livres.length > 0 && this.fila.length > 0) {
      const worker = this.livres.shift();
      const tarefa = this.fila.shift();
      this.espera.set(tarefa.id, tarefa);
      worker.postMessage({ id: tarefa.id, dados: tarefa.dados, nivel: this.nivel }, [tarefa.dados.buffer]);
    }
  }
  encerrar() {
    for (const w of this.workers) w.terminate();
  }
}

// ------------------------------------------------------------------ estado da coleta

const est = {
  info: null,
  pasta: null,
  listagem: null,
  selecionados: [],
  computador: '',
  geracao: null,
  guarda: null,
  totalArquivos: 0,
  totalBytes: 0,
  lidoBytes: 0,
  lidoArquivos: 0,
  arquivoAtual: '',
  compactadoBase: 0,
  compactadoSessao: 0,
  proximoSeqLocal: 0,
  ackSeq: 0,
  bytesEnviados: 0,
  leituraConcluida: false,
  alterados: 0,
  naoLidos: 0,
  avisoEnvio: '',
  fatal: null,
  amostras: [],
  inicio: 0,
  trabalhando: false,
};

const esperando = [];

function avisarNovidade() {
  while (esperando.length > 0) esperando.shift()();
}

function aguardarNovidade(ms = 2000) {
  return new Promise((resolver) => {
    esperando.push(resolver);
    setTimeout(resolver, ms);
  });
}

// ------------------------------------------------------------------ início

async function iniciar() {
  if (!window.showDirectoryPicker || !window.Worker || !window.WebAssembly) {
    mostrar('passo-navegador');
    return;
  }
  try {
    est.info = await pedir('GET', 'coleta');
  } catch (erro) {
    mostrar('passo-vazio');
    erroNaTela(erro.message);
    return;
  }
  const info = est.info;
  $('cliente').textContent = info.cliente;
  document.title = `Coleta de backup · ${info.cliente}`;
  if (info.pastaSistema) {
    $('dica-pasta').textContent = info.pastaSistema;
    $('dica-pasta-linha').hidden = false;
  }
  if (!['AGUARDANDO', 'RECEBENDO'].includes(info.situacao)) {
    await acompanharConclusao();
    return;
  }

  est.guarda = await abrirGuarda(chave);
  const local = await est.guarda.estado();
  if (local && local.geracao && local.geracao === info.geracao && guardaContinua(local, info.proximoSeq)) {
    prepararRetomadaLocal(local);
    return;
  }
  if (local) await est.guarda.limpar();
  if (info.proximoSeq > 0) {
    $('retomar-servidor').hidden = false;
    $('retomar-servidor-bytes').textContent = bytes(info.bytesRecebidos);
  }
  mostrar('passo-escolher');
}

/** Os pedaços guardados cobrem do que o Receptor espera até o último gravado? */
function guardaContinua(local, proximoDoReceptor) {
  for (let s = proximoDoReceptor; s < local.proximoSeqLocal; s++) {
    if (!est.guarda.tem(s)) return false;
  }
  return proximoDoReceptor <= local.proximoSeqLocal;
}

function prepararRetomadaLocal(local) {
  $('retomar-local-pendente').textContent = bytes(est.guarda.bytesPendentes);
  $('retomar-local-leitura').textContent = local.leituraConcluida
    ? 'A leitura já tinha terminado: só falta enviar.'
    : 'A leitura não tinha terminado: o navegador vai pedir para ver a pasta de novo.';
  mostrar('passo-retomar');
  $('botao-retomar').onclick = () => retomarLocal(local).catch(falhaGeral);
}

// ------------------------------------------------------------------ escolha da pasta

async function escolherPasta() {
  erroNaTela('');
  let pasta;
  try {
    pasta = await window.showDirectoryPicker({ id: 'receptor-sistema', mode: 'read' });
  } catch (erro) {
    if (erro.name === 'AbortError') return;
    erroNaTela(erro.name === 'SecurityError' || erro.name === 'NotAllowedError'
      ? 'O navegador não deixou escolher essa pasta (ele bloqueia a raiz do disco e pastas do sistema). Escolha a pasta do sistema, por exemplo C:\\sislojas.'
      : `Não consegui abrir a pasta: ${erro.message}`);
    return;
  }
  est.pasta = pasta;
  mostrar('passo-listando');
  const inicio = Date.now();
  est.listagem = await listar(pasta, ({ arquivos, bytes: b }) => {
    $('listando-contagem').textContent = `${arquivos.toLocaleString('pt-BR')} arquivos · ${bytes(b)}`;
  });
  console.info(`Listagem: ${Date.now() - inicio} ms`);
  montarSelecao();
}

function montarSelecao() {
  const { grupos, raiz } = est.listagem;
  $('nome-raiz').textContent = raiz;
  const anterior = new Set(est.info.selecaoAnterior || []);
  const usarAnterior = anterior.size > 0;
  const corpo = $('tabela-grupos');
  corpo.innerHTML = '';
  for (const g of grupos) {
    const linha = document.createElement('tr');
    const marcado = usarAnterior ? anterior.has(g.topo) : true;
    linha.innerHTML = `<td><input type="checkbox" ${marcado ? 'checked' : ''}></td><td class="nome"></td><td class="num"></td><td class="num"></td>`;
    linha.querySelector('.nome').textContent = g.nome;
    linha.querySelectorAll('.num')[0].textContent = g.arquivos.toLocaleString('pt-BR');
    linha.querySelectorAll('.num')[1].textContent = bytes(g.bytes);
    const caixa = linha.querySelector('input');
    caixa.dataset.topo = g.topo;
    caixa.onchange = atualizarTotalSelecao;
    linha.onclick = (ev) => {
      if (ev.target !== caixa) {
        caixa.checked = !caixa.checked;
        atualizarTotalSelecao();
      }
    };
    corpo.appendChild(linha);
  }
  if (est.listagem.naoLidos.length > 0) {
    $('aviso-listagem').hidden = false;
    $('aviso-listagem').textContent = `${est.listagem.naoLidos.length} pasta(s) não puderam ser listadas e ficarão de fora (aparecem no relatório).`;
  }
  if (est.info.proximoSeq > 0 && est.info.raiz && est.info.raiz !== raiz) {
    $('aviso-raiz').hidden = false;
    $('aviso-raiz').textContent = `Esta coleta começou na pasta "${est.info.raiz}" e agora foi escolhida "${raiz}". Para continuar de onde parou, escolha a mesma pasta.`;
  }
  atualizarTotalSelecao();
  mostrar('passo-selecionar');
}

function selecaoAtual() {
  return [...document.querySelectorAll('#tabela-grupos input:checked')].map((c) => c.dataset.topo);
}

function atualizarTotalSelecao() {
  const marcados = new Set(selecaoAtual());
  let arquivos = 0;
  let b = 0;
  for (const g of est.listagem.grupos) {
    if (marcados.has(g.topo)) {
      arquivos += g.arquivos;
      b += g.bytes;
    }
  }
  $('total-selecao').textContent = `${arquivos.toLocaleString('pt-BR')} arquivos · ${bytes(b)}`;
  $('botao-iniciar').disabled = marcados.size === 0;
}

// ------------------------------------------------------------------ coleta

async function iniciarColeta() {
  erroNaTela('');
  est.selecionados = selecaoAtual();
  est.computador = $('computador').value.trim();
  await lembrarPasta(chave, est.pasta);
  const entradas = filtrar(est.listagem.entradas, est.selecionados);
  await novaLeituraPeloReceptor(entradas, est.listagem.naoLidos);
}

/** Começa (ou recomeça) a leitura de onde o Receptor parou, numa geração nova. */
async function novaLeituraPeloReceptor(entradas, naoLidosIniciais) {
  await est.guarda.limpar();
  est.geracao = novaGeracao();
  contarTotais(entradas);
  let info = await registrarGeracao(null);
  for (let tentativa = 0; tentativa < 3; tentativa++) {
    try {
      await executar(entradas, info.cursor, info.proximoSeq, info.bytesRecebidos, info.cursor ? [] : naoLidosIniciais);
      return;
    } catch (erro) {
      if (!(erro instanceof PrecisaVoltar)) throw erro;
      info = await registrarGeracao(erro.caminho);
    }
  }
  throw new Error('A leitura não conseguiu um ponto estável para continuar. Recarregue a página.');
}

function contarTotais(entradas) {
  est.totalArquivos = 0;
  est.totalBytes = 0;
  for (const e of entradas) {
    if (e.tipo === 'arquivo') {
      est.totalArquivos++;
      est.totalBytes += e.tamanho || 0;
    }
  }
}

async function registrarGeracao(voltarAoInicioDe) {
  return comNovasTentativas(() => pedir('POST', 'geracao', {
    geracao: est.geracao,
    raiz: est.listagem.raiz,
    pastas: est.selecionados,
    computador: est.computador || null,
    navegador: navigator.userAgent,
    totalArquivos: est.totalArquivos,
    totalBytes: est.totalBytes,
    voltarAoInicioDe,
  }));
}

/** Continua com os pedaços guardados no navegador (mesma geração). */
async function retomarLocal(local) {
  erroNaTela('');
  est.geracao = local.geracao;
  est.selecionados = local.pastas || [];
  est.computador = local.computador || '';
  est.totalArquivos = local.totalArquivos || 0;
  est.totalBytes = local.totalBytes || 0;
  if (local.leituraConcluida) {
    est.listagem = { raiz: local.raiz, entradas: [], naoLidos: [], grupos: [] };
    await executarSoEnvio(local);
    return;
  }
  let pasta = await pastaLembrada(chave);
  if (pasta) {
    try {
      const permissao = await pasta.requestPermission({ mode: 'read' });
      if (permissao !== 'granted') pasta = null;
    } catch (e) {
      pasta = null;
    }
  }
  if (!pasta) {
    try {
      pasta = await window.showDirectoryPicker({ id: 'receptor-sistema', mode: 'read' });
    } catch (erro) {
      return;
    }
  }
  if (pasta.name !== local.raiz) {
    erroNaTela(`Escolha a mesma pasta da coleta: "${local.raiz}".`);
    return;
  }
  est.pasta = pasta;
  mostrar('passo-listando');
  est.listagem = await listar(pasta, ({ arquivos, bytes: b }) => {
    $('listando-contagem').textContent = `${arquivos.toLocaleString('pt-BR')} arquivos · ${bytes(b)}`;
  });
  const entradas = filtrar(est.listagem.entradas, est.selecionados);
  contarTotais(entradas);
  try {
    await executar(entradas, local.cursorLeitura, local.proximoSeqLocal, local.compactadoAntes || 0, [], est.info.proximoSeq);
  } catch (erro) {
    if (!(erro instanceof PrecisaVoltar)) throw erro;
    // um arquivo mudou no meio: descarta a guarda e continua do ponto confirmado pelo Receptor
    await novaLeituraPeloReceptor(entradas, []);
  }
}

async function executarSoEnvio(local) {
  est.proximoSeqLocal = local.proximoSeqLocal;
  est.ackSeq = est.info.proximoSeq;
  est.leituraConcluida = true;
  est.lidoBytes = local.lidoBytes || est.totalBytes;
  est.lidoArquivos = local.lidoArquivos || est.totalArquivos;
  est.compactadoBase = local.compactadoTotal || 0;
  est.compactadoSessao = 0;
  est.bytesEnviados = est.info.bytesRecebidos;
  prepararTelaExecucao();
  await Promise.all([enviar(), relatarProgresso()]);
  await concluir();
}

function prepararTelaExecucao() {
  est.trabalhando = true;
  est.inicio = Date.now();
  est.amostras = [];
  mostrar('passo-executando');
  $('nome-arquivo').textContent = est.info.nomeArquivo || '';
  $('modo-guarda').textContent = est.guarda.tipo === 'disco'
    ? 'Os pedaços prontos esperam o envio numa área temporária do navegador (apagada no final): se a aba fechar, o envio continua de onde parou.'
    : 'Este navegador não deixou guardar os pedaços em disco: a leitura acompanha o envio.';
  window.onbeforeunload = (ev) => {
    if (est.trabalhando) {
      ev.preventDefault();
      ev.returnValue = '';
    }
  };
  desenharProgresso();
}

/**
 * Lê (a partir de {cursor}), compacta e guarda os pedaços numerados a partir de {seqInicial}; em paralelo, envia.
 * {ackInicial}: até onde o Receptor já confirmou (para retomada local, pode ser menor que {seqInicial}).
 */
async function executar(entradas, cursor, seqInicial, compactadoAntes, naoLidosIniciais, ackInicial = seqInicial) {
  est.proximoSeqLocal = seqInicial;
  est.ackSeq = ackInicial;
  est.leituraConcluida = false;
  est.compactadoBase = compactadoAntes;
  est.compactadoSessao = 0;
  est.bytesEnviados = est.info.bytesRecebidos || 0;
  est.fatal = null;
  const nucleos = navigator.hardwareConcurrency || 2;
  const quantidade = Math.max(1, Math.min(4, Math.floor(nucleos / 2)));
  const compactadores = new Compactadores(quantidade, est.info.nivelZstd || 17);
  await compactadores.prontos;

  const gerador = blocos({
    entradas,
    tamanhoBloco: est.info.tamanhoBloco,
    inicio: cursor,
    naoLidosIniciais,
    aoAvancar: ({ arquivoAtual, lidoBytes, lidoArquivos }) => {
      est.arquivoAtual = arquivoAtual || '';
      est.lidoBytes = lidoBytes;
      est.lidoArquivos = lidoArquivos;
    },
  });

  // O primeiro bloco sai antes de mostrar a tela: se o ponto de retomada mudou, volta sem ter enviado nada
  let primeiro;
  try {
    primeiro = await gerador.next();
  } catch (erro) {
    compactadores.encerrar();
    throw erro;
  }
  prepararTelaExecucao();
  const envio = enviar();
  const relatorio = relatarProgresso();

  const salvarEstado = (fimLeitura) => est.guarda.salvarEstado({
    geracao: est.geracao,
    raiz: est.listagem.raiz,
    pastas: est.selecionados,
    computador: est.computador,
    totalArquivos: est.totalArquivos,
    totalBytes: est.totalBytes,
    cursorLeitura: fimLeitura,
    proximoSeqLocal: est.proximoSeqLocal,
    leituraConcluida: est.leituraConcluida,
    lidoBytes: est.lidoBytes,
    lidoArquivos: est.lidoArquivos,
    compactadoTotal: est.compactadoBase + est.compactadoSessao,
    compactadoAntes: est.compactadoBase + est.compactadoSessao,
  });

  const emCompactacao = [];
  const gravar = async ({ bloco, quadro }) => {
    const seq = est.proximoSeqLocal;
    const envelope = montarEnvelope({
      seq,
      geracao: est.geracao,
      quadro,
      bruto: bloco.brutoTamanho,
      fim: bloco.fim,
      arquivos: bloco.arquivos,
      naoLidos: bloco.naoLidos,
    });
    while (est.guarda.bytesPendentes + envelope.length > est.guarda.limite && est.ackSeq < est.proximoSeqLocal && !est.fatal) {
      await aguardarNovidade(1000);
    }
    if (est.fatal) throw est.fatal;
    await est.guarda.gravar(seq, envelope);
    est.proximoSeqLocal = seq + 1;
    est.compactadoSessao += quadro.length;
    est.alterados += bloco.arquivos.filter((a) => a.alterado).length;
    est.naoLidos += bloco.naoLidos.length;
    await salvarEstado(bloco.fim);
    avisarNovidade();
  };

  try {
    let atual = primeiro;
    while (!atual.done) {
      if (est.fatal) throw est.fatal;
      const bloco = atual.value;
      bloco.brutoTamanho = bloco.bruto.length;
      emCompactacao.push(compactadores.compactar(bloco.bruto).then((quadro) => ({ bloco, quadro })));
      if (emCompactacao.length > quantidade) await gravar(await emCompactacao.shift());
      atual = await gerador.next();
    }
    while (emCompactacao.length > 0) await gravar(await emCompactacao.shift());
    est.leituraConcluida = true;
    est.arquivoAtual = '';
    await salvarEstado({ fimDaLista: true });
    avisarNovidade();
  } catch (erro) {
    est.fatal = est.fatal || erro;
    avisarNovidade();
  } finally {
    compactadores.encerrar();
  }
  await envio;
  await relatorio;
  if (est.fatal) throw est.fatal;
  await concluir();
}

// ------------------------------------------------------------------ envio

async function enviar() {
  let proximoAEnviar = est.ackSeq;
  const emVoo = new Set();
  const PARALELOS = 2;

  const enviarUm = async (seq) => {
    let pausa = 1000;
    while (!est.fatal) {
      try {
        if (seq < est.ackSeq) return;
        const envelope = await est.guarda.ler(seq);
        const tempoMs = 60000 + Math.ceil(envelope.length / 10);
        const resposta = await pedir('PUT', `partes/${seq}?geracao=${est.geracao}`, envelope, { tempoMs, binario: true });
        est.avisoEnvio = '';
        if (resposta.proximoSeq > est.ackSeq) {
          for (let s = est.ackSeq; s < resposta.proximoSeq; s++) await est.guarda.remover(s);
          est.ackSeq = resposta.proximoSeq;
        }
        est.bytesEnviados = resposta.bytesRecebidos;
        est.amostras.push([Date.now(), est.bytesEnviados]);
        while (est.amostras.length > 2 && Date.now() - est.amostras[0][0] > 60000) est.amostras.shift();
        avisarNovidade();
        return;
      } catch (erro) {
        if (erro instanceof ErroHttp && ['GERACAO_ANTIGA', 'CANCELADA', 'VENCIDO', 'LINK_INVALIDO', 'JA_CONCLUIDA'].includes(erro.codigo)) {
          est.fatal = erro;
          avisarNovidade();
          return;
        }
        if (erro instanceof ErroHttp && erro.codigo === 'SEM_ESPACO') {
          est.avisoEnvio = erro.message;
        } else if (erro instanceof ErroHttp && erro.status === 422) {
          est.avisoEnvio = 'Um pedaço chegou corrompido; mandando de novo.';
          pausa = 1000;
        } else if (erro instanceof ErroHttp && erro.codigo === 'PEDACO_ADIANTADO') {
          est.avisoEnvio = '';
        } else {
          est.avisoEnvio = `Sem conexão com o Receptor (${erro.name === 'AbortError' ? 'tempo esgotado' : erro.message}). Tentando de novo em ${Math.round(pausa / 1000)} s…`;
        }
        await espera(pausa);
        pausa = Math.min(pausa * 2, 30000);
      }
    }
  };

  while (!est.fatal) {
    if (proximoAEnviar < est.ackSeq) proximoAEnviar = est.ackSeq;
    while (emVoo.size < PARALELOS && proximoAEnviar < est.proximoSeqLocal) {
      const seq = proximoAEnviar++;
      const tarefa = enviarUm(seq).finally(() => {
        emVoo.delete(tarefa);
        avisarNovidade();
      });
      emVoo.add(tarefa);
    }
    if (est.leituraConcluida && est.ackSeq >= est.proximoSeqLocal && emVoo.size === 0) return;
    await aguardarNovidade(2000);
  }
}

async function relatarProgresso() {
  let ultimo = 0;
  while (est.trabalhando && !est.fatal) {
    desenharProgresso();
    if (Date.now() - ultimo > 10000) {
      ultimo = Date.now();
      pedir('POST', 'progresso', {
        fase: est.leituraConcluida ? 'enviando' : 'lendo',
        lidoBytes: est.lidoBytes,
        lidoArquivos: est.lidoArquivos,
        compactadoBytes: est.compactadoBase + est.compactadoSessao,
        bufferBytes: est.guarda.bytesPendentes,
        arquivoAtual: est.arquivoAtual,
      }, { tempoMs: 20000 }).catch(() => {});
    }
    if (est.leituraConcluida && est.ackSeq >= est.proximoSeqLocal) return;
    await espera(1000);
  }
}

function velocidade() {
  const a = est.amostras;
  if (a.length < 2) return 0;
  const [t0, b0] = a[0];
  const [t1, b1] = a[a.length - 1];
  if (Date.now() - t1 > 45000) return 0;
  return (b1 - b0) / Math.max(1, (Date.now() - t0) / 1000);
}

function desenharProgresso() {
  const pctLeitura = est.totalBytes > 0 ? Math.min(100, (est.lidoBytes / est.totalBytes) * 100) : (est.leituraConcluida ? 100 : 0);
  $('barra-leitura').style.width = `${est.leituraConcluida ? 100 : pctLeitura}%`;
  $('texto-leitura').textContent = est.leituraConcluida
    ? `Leitura concluída: ${est.totalArquivos.toLocaleString('pt-BR')} arquivos, ${bytes(est.totalBytes)}`
    : `${bytes(est.lidoBytes)} de ${bytes(est.totalBytes)} · ${est.lidoArquivos.toLocaleString('pt-BR')} de ${est.totalArquivos.toLocaleString('pt-BR')} arquivos`;
  $('arquivo-atual').textContent = est.leituraConcluida ? '' : est.arquivoAtual;

  const compactado = est.compactadoBase + est.compactadoSessao;
  const estimativa = est.leituraConcluida ? compactado
    : est.lidoBytes > 0 ? (compactado * est.totalBytes) / est.lidoBytes : null;
  const pctEnvio = estimativa ? Math.min(100, (est.bytesEnviados / estimativa) * 100) : 0;
  $('barra-envio').style.width = `${pctEnvio}%`;
  const vel = velocidade();
  const restante = estimativa ? Math.max(0, estimativa - est.bytesEnviados) : null;
  $('texto-envio').textContent = `${bytes(est.bytesEnviados)} enviados de ${estimativa ? (est.leituraConcluida ? '' : '~') + bytes(estimativa) : '—'} compactados`;
  $('velocidade').textContent = vel > 0 ? `${bytes(vel)}/s (${(vel * 8 / 1e6).toLocaleString('pt-BR', { maximumFractionDigits: 2 })} Mbps)` : '—';
  $('previsao').textContent = vel > 0 && restante != null ? duracao(restante / vel) : 'calculando…';
  $('taxa').textContent = est.lidoBytes > 0 && compactado > 0 ? `${(est.lidoBytes / compactado).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}×` : '—';
  $('guardado').textContent = bytes(est.guarda ? est.guarda.bytesPendentes : 0);
  $('aviso-envio').textContent = est.avisoEnvio;
  $('aviso-envio').hidden = !est.avisoEnvio;
  const avisos = [];
  if (est.alterados > 0) avisos.push(`${est.alterados} arquivo(s) mudaram enquanto eram lidos (sistema em uso): vão marcados no relatório.`);
  if (est.naoLidos > 0) avisos.push(`${est.naoLidos} arquivo(s) não puderam ser lidos: vão listados no relatório.`);
  $('avisos-leitura').textContent = avisos.join(' ');
  $('avisos-leitura').hidden = avisos.length === 0;
  const lenta = vel > 0 && restante != null && restante / vel > 3600;
  $('dica-4g').hidden = !lenta;
}

// ------------------------------------------------------------------ conclusão

async function concluir() {
  $('texto-envio').textContent = 'Todos os pedaços chegaram. Fechando o arquivo…';
  await comNovasTentativas(() => pedir('POST', 'concluir', { geracao: est.geracao, totalPartes: est.proximoSeqLocal }, { tempoMs: 120000 }));
  est.trabalhando = false;
  window.onbeforeunload = null;
  await est.guarda.limpar();
  await apagarGuarda(chave);
  await esquecerPasta(chave);
  await acompanharConclusao();
}

async function acompanharConclusao() {
  mostrar('passo-concluido');
  for (;;) {
    let info;
    try {
      info = await pedir('GET', 'coleta');
    } catch (e) {
      await espera(5000);
      continue;
    }
    const titulo = $('resultado-titulo');
    const texto = $('resultado-texto');
    if (info.situacao === 'CONFERINDO' || info.situacao === 'MONTANDO') {
      titulo.textContent = 'O backup chegou. O Receptor está conferindo…';
      texto.textContent = 'Ele recalcula o SHA-256 de cada arquivo e compara com o que foi lido aqui.';
    } else if (info.situacao === 'INTEGRA') {
      titulo.textContent = '✔ Backup recebido inteiro e conferido';
      titulo.className = 'ok';
      texto.textContent = `${info.conferencia ? info.conferencia.mensagem : ''} Pode fechar esta página.`;
      return;
    } else if (info.situacao === 'DIVERGENTE') {
      titulo.textContent = 'O backup chegou, mas a conferência encontrou diferenças';
      titulo.className = 'ruim';
      texto.textContent = `${info.conferencia ? info.conferencia.mensagem : ''} Veja os detalhes no Receptor.`;
      return;
    } else if (info.situacao === 'CANCELADA') {
      titulo.textContent = 'Esta coleta foi cancelada no Receptor.';
      texto.textContent = '';
      return;
    } else {
      titulo.textContent = `Situação: ${info.situacao}`;
      texto.textContent = info.mensagem || '';
      return;
    }
    await espera(3000);
  }
}

async function comNovasTentativas(acao) {
  let pausa = 1000;
  for (;;) {
    try {
      return await acao();
    } catch (erro) {
      if (erro instanceof ErroHttp && erro.status < 500 && erro.status !== 408 && erro.status !== 429) throw erro;
      erroNaTela(`Sem conexão com o Receptor; tentando de novo em ${Math.round(pausa / 1000)} s…`);
      await espera(pausa);
      pausa = Math.min(pausa * 2, 30000);
      erroNaTela('');
    }
  }
}

function falhaGeral(erro) {
  est.trabalhando = false;
  window.onbeforeunload = null;
  console.error(erro);
  const texto = erro instanceof ErroHttp ? erro.message : `Falhou: ${erro.message || erro}`;
  erroNaTela(`${texto} Recarregue a página para continuar de onde parou.`);
}

// ------------------------------------------------------------------ ligações

$('botao-escolher').onclick = () => escolherPasta().catch(falhaGeral);
$('botao-trocar').onclick = () => escolherPasta().catch(falhaGeral);
$('botao-iniciar').onclick = () => iniciarColeta().catch(falhaGeral);
$('marcar-todos').onclick = () => {
  document.querySelectorAll('#tabela-grupos input').forEach((c) => { c.checked = true; });
  atualizarTotalSelecao();
};
$('botao-descartar').onclick = async () => {
  await est.guarda.limpar();
  await apagarGuarda(chave);
  est.guarda = await abrirGuarda(chave);
  mostrar('passo-escolher');
};
iniciar().catch(falhaGeral);
