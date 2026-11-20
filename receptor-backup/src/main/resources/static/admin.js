// Tela do Receptor: clientes, links de coleta, andamento, conferência, extração e espaço.
const $ = (id) => document.getElementById(id);

async function api(metodo, caminho, corpo) {
  const opcoes = { method: metodo, headers: { 'X-Receptor': '1' }, cache: 'no-store' };
  if (corpo !== undefined) {
    opcoes.headers['Content-Type'] = 'application/json';
    opcoes.body = JSON.stringify(corpo);
  }
  const resposta = await fetch(`/api${caminho}`, opcoes);
  const texto = await resposta.text();
  const dados = texto ? JSON.parse(texto) : null;
  if (!resposta.ok) throw new Error((dados && dados.mensagem) || `Erro ${resposta.status}`);
  return dados;
}

function el(tag, atributos = {}, ...filhos) {
  const elemento = document.createElement(tag);
  for (const [chave, valor] of Object.entries(atributos)) {
    if (valor == null || valor === false) continue;
    if (chave === 'class') elemento.className = valor;
    else if (chave.startsWith('on')) elemento.addEventListener(chave.slice(2), valor);
    else if (chave === 'style') elemento.setAttribute('style', valor);
    else elemento[chave] = valor;
  }
  for (const filho of filhos.flat()) {
    if (filho == null || filho === false) continue;
    elemento.append(filho instanceof Node ? filho : String(filho));
  }
  return elemento;
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
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  return `${h} h ${String(m % 60).padStart(2, '0')} min`;
}

function quando(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function erroNaTela(texto) {
  $('erro').textContent = texto || '';
  $('erro').hidden = !texto;
}

async function acao(fn) {
  erroNaTela('');
  try {
    await fn();
    await atualizar();
  } catch (e) {
    erroNaTela(e.message);
  }
}

const SITUACOES = {
  AGUARDANDO: 'aguardando a página',
  RECEBENDO: 'recebendo',
  MONTANDO: 'fechando o arquivo',
  CONFERINDO: 'conferindo',
  INTEGRA: 'íntegro',
  DIVERGENTE: 'com diferenças',
  FALHOU: 'falhou',
  CANCELADA: 'cancelada',
};

let estado = null;
let clientes = [];
let editando = null;
const abertos = new Set();

// ------------------------------------------------------------------ desenho

function desenharTunel() {
  const t = estado.tunel;
  const caixa = $('tunel');
  caixa.className = `tunel ${t.modo === 'ATIVO' || t.modo === 'FIXO' ? 'ativo' : t.modo === 'INICIANDO' ? '' : 'ruim'}`;
  const partes = [el('span', { class: 'ponto' }), t.endereco ? `Endereço público: ${t.endereco}` : t.mensagem];
  if (t.modo === 'ATIVO') {
    partes.push(el('button', { class: 'link', title: 'Abre outro túnel (o endereço muda)', onclick: () => acao(() => api('POST', '/tunel/reiniciar')) }, 'reiniciar'));
  }
  caixa.replaceChildren(...partes);
  $('pasta-dados').textContent = estado.pastaDados;
}

function desenharClientes() {
  const seletor = $('cliente-coleta');
  const escolhido = seletor.value;
  seletor.replaceChildren(...clientes.map((c) => el('option', { value: c.nome }, c.nome)));
  if (clientes.some((c) => c.nome === escolhido)) seletor.value = escolhido;
  $('botao-gerar').disabled = clientes.length === 0;

  const corpo = $('clientes');
  if (clientes.length === 0) {
    corpo.replaceChildren(el('tr', {}, el('td', { colSpan: 4, class: 'vazio' }, 'Nenhum cliente. Cadastre abaixo.')));
    return;
  }
  corpo.replaceChildren(...clientes.map((c) => el('tr', {},
    el('td', {}, c.nome),
    el('td', {}, c.pastaSistema ? el('code', {}, c.pastaSistema) : '—'),
    el('td', { class: 'nota' }, (c.ultimaSelecao || []).map((s) => (s === '__arquivos-da-raiz__' ? '(arquivos soltos)' : s)).join(', ') || '—'),
    el('td', { class: 'num' },
      el('button', { class: 'link', onclick: () => editarCliente(c) }, 'editar'),
      el('button', { class: 'link', onclick: () => {
        if (confirm(`Remover o cliente "${c.nome}"? Os backups dele continuam na pasta.`)) acao(() => api('DELETE', `/clientes/${encodeURIComponent(c.nome)}`));
      } }, 'remover')),
  )));
}

function barra(pct) {
  return el('div', { class: 'barra' }, el('div', { style: `width:${Math.max(0, Math.min(100, pct || 0))}%` }));
}

function desenharColeta(c) {
  const recebendo = c.situacao === 'AGUARDANDO' || c.situacao === 'RECEBENDO';
  const partes = [];
  partes.push(el('div', { class: 'titulo' },
    el('div', {}, el('strong', {}, c.cliente), ' ', el('span', { class: `selo ${c.situacao}` }, SITUACOES[c.situacao] || c.situacao)),
    el('span', { class: 'nota' }, `criada ${quando(c.criadaEm)}${c.concluidaEm ? ` · concluída ${quando(c.concluidaEm)}` : ''}`)));
  partes.push(el('p', { class: 'meta' }, `${c.nome}.tar.zst`,
    c.raiz ? ` · pasta ${c.raiz}` : '', c.computador ? ` · ${c.computador}` : '',
    c.pastas && c.pastas.length ? ` · ${c.pastas.map((s) => (s === '__arquivos-da-raiz__' ? '(soltos)' : s)).join(', ')}` : ''));

  if (c.situacao === 'RECEBENDO') {
    const p = c.progresso;
    const lido = p ? p.lidoBytes : 0;
    const pctLeitura = c.totalBytes ? (lido / c.totalBytes) * 100 : 0;
    const estimativa = p && p.lidoBytes > 0 ? (p.compactadoBytes * c.totalBytes) / p.lidoBytes : null;
    const pctEnvio = estimativa ? (c.bytesRecebidos / estimativa) * 100 : 0;
    partes.push(el('div', { class: 'barras' },
      el('div', {}, barra(p && p.fase === 'enviando' ? 100 : pctLeitura),
        el('p', {}, p ? (p.fase === 'enviando' ? `Leitura concluída (${bytes(c.totalBytes)})` : `Lidos ${bytes(lido)} de ${bytes(c.totalBytes)}`) : 'Aguardando a página informar o andamento')),
      el('div', {}, barra(pctEnvio),
        el('p', {}, `Recebidos ${bytes(c.bytesRecebidos)}${estimativa ? ` de ~${bytes(estimativa)}` : ''}`,
          c.velocidade > 0 ? ` · ${bytes(c.velocidade)}/s` : '',
          c.previsaoSegundos != null ? ` · faltam ~${duracao(c.previsaoSegundos)}` : '')),
    ));
    if (c.ultimoContato && Date.now() - new Date(c.ultimoContato).getTime() > 60000) {
      partes.push(el('p', { class: 'alerta' }, `Sem contato da página desde ${quando(c.ultimoContato)}. Se a aba foi fechada, abra o link de novo: continua de onde parou.`));
    }
  }
  if (c.situacao === 'CONFERINDO' && c.andamentoTrabalho != null && c.resumo) {
    partes.push(barra((c.andamentoTrabalho / Math.max(1, c.resumo.bytesOriginais)) * 100));
  }
  if (c.resumo) {
    partes.push(el('p', { class: 'meta' },
      `${c.resumo.arquivos.toLocaleString('pt-BR')} arquivos · ${bytes(c.resumo.bytesOriginais)} originais → ${bytes(c.tamanhoArquivo)} no disco`,
      c.resumo.bytesOriginais && c.tamanhoArquivo ? ` (${(c.resumo.bytesOriginais / c.tamanhoArquivo).toLocaleString('pt-BR', { maximumFractionDigits: 1 })}×)` : '',
      c.inicioEnvio && c.concluidaEm ? ` · levou ${duracao((new Date(c.concluidaEm) - new Date(c.inicioEnvio)) / 1000)}` : ''));
  }
  if (c.conferencia && c.situacao !== 'CONFERINDO') {
    partes.push(el('p', { class: c.situacao === 'INTEGRA' ? 'meta ok' : 'alerta' }, c.conferencia.mensagem));
  }
  if (c.situacao !== 'INTEGRA' && c.situacao !== 'DIVERGENTE' && c.mensagem) {
    partes.push(el('p', { class: 'meta' }, c.mensagem));
  }
  if (c.extracao) {
    const x = c.extracao;
    if (x.situacao === 'EXTRAINDO') {
      partes.push(barra(x.total ? ((c.andamentoTrabalho || 0) / x.total) * 100 : 0), el('p', { class: 'meta' }, `Extraindo em ${x.destino}…`));
    } else {
      partes.push(el('p', { class: x.situacao === 'EXTRAIDO' ? 'meta' : 'alerta' }, `Extração: ${x.mensagem || x.situacao} `, el('code', {}, x.destino)));
    }
  }

  const detalhes = [];
  if (c.alterados && c.alterados.length) detalhes.push(['Mudaram durante a leitura (sistema em uso)', c.alterados]);
  if (c.naoLidos && c.naoLidos.length) detalhes.push(['Não lidos', c.naoLidos]);
  if (c.conferencia) {
    if (c.conferencia.divergentes && c.conferencia.divergentes.length) detalhes.push(['Divergentes na conferência', c.conferencia.divergentes]);
    if (c.conferencia.faltando && c.conferencia.faltando.length) detalhes.push(['Faltando no arquivo', c.conferencia.faltando]);
    if (c.conferencia.sobrando && c.conferencia.sobrando.length) detalhes.push(['A mais no arquivo', c.conferencia.sobrando]);
  }
  for (const [titulo, lista] of detalhes) {
    const chave = `${c.id}:${titulo}`;
    const d = el('details', { open: abertos.has(chave), ontoggle: (ev) => (ev.target.open ? abertos.add(chave) : abertos.delete(chave)) },
      el('summary', {}, `${titulo} (${lista.length})`), el('ul', {}, lista.map((x) => el('li', {}, x))));
    partes.push(d);
  }

  const acoes = [];
  if (recebendo) {
    const link = c.link || c.linkLocal;
    acoes.push(el('button', { class: 'forte', onclick: () => copiar(link) }, c.link ? 'Copiar link' : 'Copiar link (só nesta máquina)'));
    acoes.push(el('button', { class: 'perigo', onclick: () => confirm('Cancelar esta coleta? O que já chegou é apagado.') && acao(() => api('POST', `/coletas/${c.id}/cancelar`)) }, 'Cancelar'));
  }
  if (c.situacao === 'INTEGRA' || c.situacao === 'DIVERGENTE') {
    const extraindo = c.extracao && c.extracao.situacao === 'EXTRAINDO';
    acoes.push(el('button', { class: 'forte', disabled: extraindo, onclick: () => acao(() => api('POST', `/coletas/${c.id}/extrair`, {})) }, 'Extrair'));
    acoes.push(el('button', { disabled: extraindo, onclick: () => {
      const destino = prompt('Extrair em qual pasta? (caminho completo; precisa estar vazia ou não existir)', 'D:\\Testes\\');
      if (destino) acao(() => api('POST', `/coletas/${c.id}/extrair`, { destino }));
    } }, 'Extrair em…'));
    acoes.push(el('button', { onclick: () => acao(() => api('POST', `/coletas/${c.id}/mostrar`)) }, 'Mostrar na pasta'));
    acoes.push(el('button', { onclick: () => acao(() => api('POST', `/coletas/${c.id}/conferir`)) }, 'Conferir de novo'));
  }
  if (!recebendo && c.situacao !== 'CONFERINDO' && c.situacao !== 'MONTANDO') {
    acoes.push(el('button', { class: 'perigo', onclick: () => confirm(`Apagar ${c.nome}.tar.zst? Não dá para desfazer (a pasta extraída, se houver, fica).`) && acao(() => api('DELETE', `/coletas/${c.id}`)) }, 'Apagar'));
  }
  partes.push(el('div', { class: 'acoes' }, acoes));
  return el('div', { class: 'coleta' }, partes);
}

async function copiar(texto) {
  try {
    await navigator.clipboard.writeText(texto);
  } catch (e) {
    $('link-texto').value = texto;
    $('link-texto').select();
    document.execCommand('copy');
  }
}

// ------------------------------------------------------------------ dados

async function atualizar() {
  const [e, lista, cl, espaco] = await Promise.all([api('GET', '/estado'), api('GET', '/coletas'), api('GET', '/clientes'), api('GET', '/espaco')]);
  estado = e;
  clientes = cl;
  desenharTunel();
  if (!editando) desenharClientes();
  const area = $('coletas');
  area.replaceChildren(...(lista.length ? lista.map(desenharColeta) : [el('p', { class: 'vazio' }, 'Nenhuma coleta ainda.')]));
  $('espaco').textContent = `${bytes(espaco.total)} em backups · ${bytes(espaco.livre)} livres no disco`;
}

function editarCliente(c) {
  editando = c.nome;
  $('cliente-nome').value = c.nome;
  $('cliente-pasta').value = c.pastaSistema || '';
  $('botao-salvar-cliente').textContent = 'Salvar';
  $('botao-cancelar-edicao').hidden = false;
  $('cliente-nome').focus();
}

function pararEdicao() {
  editando = null;
  $('form-cliente').reset();
  $('botao-salvar-cliente').textContent = 'Cadastrar';
  $('botao-cancelar-edicao').hidden = true;
  desenharClientes();
}

$('form-cliente').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const corpo = { nome: $('cliente-nome').value.trim(), pastaSistema: $('cliente-pasta').value.trim() };
  acao(async () => {
    if (editando) await api('PUT', `/clientes/${encodeURIComponent(editando)}`, corpo);
    else await api('POST', '/clientes', corpo);
    pararEdicao();
  });
});
$('botao-cancelar-edicao').onclick = pararEdicao;
$('botao-novo-cliente-atalho').onclick = () => $('cliente-nome').focus();

$('botao-gerar').onclick = () => acao(async () => {
  const c = await api('POST', '/coletas', { cliente: $('cliente-coleta').value });
  const link = c.link || c.linkLocal;
  $('link-gerado').hidden = false;
  $('link-texto').value = link;
  $('link-aviso').textContent = c.link
    ? `Vale por ${estado.validadeLinkHoras} h sem uso. Se o Receptor reiniciar, o endereço do túnel muda: copie o link de novo aqui.`
    : 'Sem túnel ativo: este link só abre nesta máquina (bom para testar). Instale o cloudflared para gerar o endereço público.';
  await copiar(link);
});
$('botao-copiar').onclick = () => copiar($('link-texto').value);

async function laco() {
  try {
    await atualizar();
  } catch (e) {
    erroNaTela(`Sem resposta do Receptor: ${e.message}`);
  }
  setTimeout(laco, 2000);
}
laco();
