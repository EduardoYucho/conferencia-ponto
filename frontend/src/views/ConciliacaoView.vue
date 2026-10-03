<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import { dataBR, exato, momento, seloDoTom, textoDoTom } from '@/utils/horas'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'
import ModalAjusteBatidas from '@/components/ModalAjusteBatidas.vue'

/**
 * Conferir com o RH, em três passos: (1) a pessoa envia o PDF do relatório de banco de horas do RH, (2) o servidor
 * compara dia a dia em segundo plano e (3) cada dia diferente aparece aqui para ela decidir: usar o do RH, manter
 * o dela ou ajustar as batidas à mão. Nada muda sozinho.
 *
 * As frases, os dois lados de cada dia, o tamanho da diferença e os botões que aparecem vêm prontos do servidor
 * (`tela` de cada diferença e de cada relatório; `comparacao` e `contagem` do resumo): a tela só mostra.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const { ultimaConciliacao, ultimoEvento, reconectouEm } = storeToRefs(ponto)

/** Quantas diferenças aparecem antes do botão "Mostrar mais dias". */
const PRIMEIRAS = 20
const DECIDIDAS = ['DECIDIDAS', 'ACEITO_RH', 'MANTIDO_LOCAL', 'RESOLVIDA']

// ------------------------------------------------------------------ estado
const resumo = ref(null)
const divergencias = ref([])
/** A lista do filtro em tela já chegou (antes disso não dá para dizer "nada para decidir": é desconhecido). */
const listaCarregada = ref(false)
const filtro = ref('PENDENTE')
const filtroTipo = ref(null)
const limite = ref(PRIMEIRAS)
const carregando = ref(false)
const erro = ref(null)
const reconferindo = ref(false)
/** Diferença com uma ação em andamento: { id, acao }. */
const ocupado = ref(null)
/** "Manter o meu" aberto numa diferença: { id, observacao }. */
const mantendo = ref(null)
/** Diferenças já decididas com os dois lados à mostra. */
const abertas = ref(new Set())
const enviando = ref([]) // [{ nome, estado: 'enviando' | 'ok' | 'erro', mensagem }]
const arrastando = ref(false)
const campoArquivo = ref(null)
const ajuste = ref({ aberto: false, data: null, registro: null, sugestao: null, motivo: '' })
/** Janela "Usar o do RH em vários dias". */
const lote = ref({ aberto: false, tipos: [], aplicando: false })
const falhasLote = ref([])
/** Relatório esperando a confirmação para ser removido. */
const remocao = ref(null)
const removendo = ref(false)
const janelaLote = ref(null)
const janelaRemocao = ref(null)
let timerRecarga = null
let pedido = 0

const meus = computed(() => auth.vendoOsProprios && auth.ehTitular)
const relatorios = computed(() => resumo.value?.relatorios ?? [])
const comparacao = computed(() => resumo.value?.comparacao ?? null)

// ------------------------------------------------------------------ leitura
async function carregar() {
  // trocando de filtro depressa, as respostas podem chegar fora de ordem: só a do último pedido entra na tela
  const meu = ++pedido
  carregando.value = true
  try {
    const [r, d] = await Promise.all([pontoApi.resumoConciliacao(), pontoApi.divergencias(filtro.value)])
    if (meu !== pedido) return
    resumo.value = r
    divergencias.value = d
    listaCarregada.value = true
    erro.value = null
  } catch (e) {
    if (meu === pedido) erro.value = e
  } finally {
    if (meu === pedido) carregando.value = false
  }
}

onMounted(() => {
  carregar()
  window.addEventListener('keydown', aoTeclar)
})
onBeforeUnmount(() => {
  pedido++ // respostas que ainda estão a caminho não entram numa tela que já saiu
  clearTimeout(timerRecarga)
  window.removeEventListener('keydown', aoTeclar)
})

watch(filtro, () => {
  limite.value = PRIMEIRAS
  filtroTipo.value = null
  mantendo.value = null
  // a lista do filtro anterior não fica sob o filtro novo
  divergencias.value = []
  listaCarregada.value = false
  carregar()
})

/** Relatório comparado, diferença resolvida ou um dia mudou (PDF, ajuste): recarrega. */
function agendarRecarga() {
  clearTimeout(timerRecarga)
  timerRecarga = setTimeout(carregar, 600)
}
watch(ultimaConciliacao, (e) => e && agendarRecarga())
watch(ultimoEvento, (e) => e?.tipo === 'jornada-atualizada' && agendarRecarga())
// a conexão voltou depois de uma queda: um relatório que ficou em "Comparando…" pode já ter terminado
watch(reconectouEm, (quando) => quando && agendarRecarga())

/**
 * Faz um pedido de alteração ao servidor. Devolve { ok, dados }; se o servidor recusar, o motivo já sai no aviso.
 * O que vem depois de um pedido que deu certo (mensagem, recarga) fica fora daqui: não pode parecer falha dele.
 */
async function pedir(chamada) {
  try {
    return { ok: true, dados: await chamada() }
  } catch (e) {
    avisar(mensagemDe(e), 'erro')
    return { ok: false }
  }
}

// ------------------------------------------------------------------ passo 1: enviar o relatório
async function enviarArquivos(arquivos) {
  const pdfs = [...arquivos].filter((a) => a.name.toLowerCase().endsWith('.pdf'))
  if (!pdfs.length) {
    avisar('Escolha o PDF do relatório de banco de horas que o RH emite.', 'erro')
    return
  }
  for (const arquivo of pdfs) {
    const item = { nome: arquivo.name, estado: 'enviando', mensagem: 'Enviando…' }
    enviando.value = [item, ...enviando.value].slice(0, 8)
    try {
      const r = await pontoApi.enviarRelatorioRh(arquivo)
      Object.assign(item, { estado: 'ok', mensagem: `Recebido: ${r.tela.periodo} (${r.diasLidos} dias). O sistema está comparando dia a dia; o resultado aparece logo abaixo.` })
    } catch (e) {
      Object.assign(item, { estado: 'erro', mensagem: mensagemDe(e) })
    }
    enviando.value = [...enviando.value]
  }
  agendarRecarga()
}

function aoSoltar(evento) {
  arrastando.value = false
  if (auth.podeEscrever) enviarArquivos(evento.dataTransfer.files)
}

function aoEscolher(evento) {
  enviarArquivos(evento.target.files)
  evento.target.value = ''
}

// ------------------------------------------------------------------ passo 2: resultado e relatórios
async function reconferir() {
  reconferindo.value = true
  const refeita = await pedir(() => pontoApi.reconferir())
  reconferindo.value = false
  if (!refeita.ok) return
  avisar('Comparação refeita com todos os relatórios enviados.')
  await carregar()
}

async function pedirRemocao(r) {
  remocao.value = r
  await nextTick()
  janelaRemocao.value?.querySelector('button')?.focus()
}

async function confirmarRemocao() {
  const r = remocao.value
  if (!r) return
  removendo.value = true
  const removido = await pedir(() => pontoApi.excluirRelatorioRh(r.id))
  removendo.value = false
  remocao.value = null
  if (!removido.ok) return
  avisar('Relatório removido.')
  carregar()
}

// ------------------------------------------------------------------ passo 3: as diferenças
/** Filtros de cima (o servidor de versão anterior não conhece "já decididas": mostra as três separadas). */
const filtros = computed(() => (resumo.value?.contagem
  ? [
      { valor: 'PENDENTE', rotulo: 'Para decidir' },
      { valor: 'DECIDIDAS', rotulo: 'Já decididas' },
      { valor: 'TODAS', rotulo: 'Todas' },
    ]
  : [
      { valor: 'PENDENTE', rotulo: 'Para decidir' },
      { valor: 'ACEITO_RH', rotulo: 'Usei o do RH' },
      { valor: 'MANTIDO_LOCAL', rotulo: 'Mantive o meu' },
      { valor: 'RESOLVIDA', rotulo: 'Ficaram iguais' },
      { valor: 'TODAS', rotulo: 'Todas' },
    ]))
/** Dentro de "Já decididas": como cada uma foi decidida. */
const FILTROS_DECIDIDAS = [
  { valor: 'DECIDIDAS', rotulo: 'Todas as decididas' },
  { valor: 'ACEITO_RH', rotulo: 'Usei o do RH' },
  { valor: 'MANTIDO_LOCAL', rotulo: 'Mantive o meu' },
  { valor: 'RESOLVIDA', rotulo: 'Ficaram iguais de outro jeito' },
]
const vendoDecididas = computed(() => !!resumo.value?.contagem && DECIDIDAS.includes(filtro.value))
const filtroAtivo = (valor) => filtro.value === valor || (valor === 'DECIDIDAS' && vendoDecididas.value)
/** Quantas diferenças há em cada filtro (conta do servidor). */
const quantas = (valor) => resumo.value?.contagem?.[valor] ?? resumo.value?.porStatus?.[valor] ?? null

const tiposComPendencia = computed(() => (resumo.value?.tipos ?? []).filter((t) => t.pendentes > 0))
/** Tipos em que há dias que dá para resolver com o do RH (o servidor diz quantos). */
const tiposParaLote = computed(() => (resumo.value?.tipos ?? []).filter((t) => (t.aceitaveis ?? t.pendentes) > 0))
const nomeDoTipo = (t) => t.nome ?? t.rotulo
/** No celular, as pílulas de tipo ficam atrás de um botão (são muitas). */
const tiposAbertos = ref(false)
const tipoEmTela = computed(() => tiposComPendencia.value.find((t) => t.tipo === filtroTipo.value) ?? null)

/** Mais recentes primeiro: é onde as decisões importam para o período atual do banco de horas. */
const filtradas = computed(() => divergencias.value
  .filter((d) => !filtroTipo.value || d.tipo === filtroTipo.value)
  .slice()
  .sort((a, b) => b.data.localeCompare(a.data)))
const visiveis = computed(() => filtradas.value.slice(0, limite.value))
/** Que marcas aparecem nas batidas em tela (a legenda só explica as que estão à vista). */
const temMarca = (marca) => visiveis.value.some((d) => [...d.tela.sistema.horarios, ...d.tela.rh.horarios].some((h) => h.marca === marca))
const temDiferentes = computed(() => temMarca('DIFERENTE'))
const temSegundos = computed(() => temMarca('SEGUNDOS'))

/**
 * O que acontece ao usar as batidas do RH (a frase vem do servidor e é a mesma em todos os dias de batidas:
 * aparece uma vez, acima da lista; nos dias de folga ou feriado a frase é própria do dia e fica no cartão).
 */
const notaDoUsarRh = computed(() => (auth.podeEscrever
  ? visiveis.value.find((d) => d.tela.acoes.usarRh && !d.tela.acoes.folgas)?.tela.aoUsarRh ?? null
  : null))

/** Os dois lados do dia, na ordem em que aparecem. */
function lados(d) {
  return [
    { chave: 'sistema', nome: 'No sistema', doRh: false, saldoExato: exato(d.local?.saldoDiarioSegundos), ...d.tela.sistema },
    { chave: 'rh', nome: 'No relatório do RH', doRh: true, saldoExato: exato(d.rh?.saldoSegundos), ...d.tela.rh },
  ]
}

const classeDaHora = (h) => ({
  DIFERENTE: 'rounded-md bg-atencao-suave px-1.5 text-atencao',
  SEGUNDOS: 'underline decoration-dotted underline-offset-4',
}[h.marca] ?? '')

const quemDecidiu = (d) => (d.resolvidaPor === 'sistema' ? 'pelo sistema' : `por ${d.resolvidaPor}`)

function alternarDetalhe(d) {
  const novo = new Set(abertas.value)
  if (!novo.delete(d.id)) novo.add(d.id)
  abertas.value = novo
}

async function executar(d, acao, chamada, mensagem) {
  ocupado.value = { id: d.id, acao }
  try {
    const feito = await pedir(chamada)
    if (!feito.ok) return
    avisar(typeof mensagem === 'function' ? mensagem(feito.dados) : mensagem)
    await carregar()
  } finally {
    ocupado.value = null
  }
}

function usarRh(d) {
  executar(d, 'usarRh', () => pontoApi.aceitarDivergencia(d.id), (depois) =>
    depois?.status === 'PENDENTE'
      ? `${d.tela.dia}: usei o do RH, mas o dia ainda tem diferença (${(depois.tela?.tipo ?? depois.tipoRotulo ?? '').toLowerCase()}).`
      : `${d.tela.dia}: o sistema ficou igual ao RH.`)
}

async function iniciarManter(d) {
  mantendo.value = { id: d.id, observacao: '' }
  await nextTick()
  document.getElementById(`obs-${d.id}`)?.focus()
}

function confirmarManter(d) {
  const observacao = mantendo.value?.observacao?.trim() || null
  mantendo.value = null
  executar(d, 'manter', () => pontoApi.manterDivergencia(d.id, observacao), `${d.tela.dia}: mantido como está no sistema.`)
}

function reabrir(d) {
  executar(d, 'reabrir', () => pontoApi.reabrirDivergencia(d.id), `${d.tela.dia}: a diferença voltou para decidir.`)
}

/** Abre o ajuste do dia já com as batidas do RH sugeridas. */
function ajustarManual(d) {
  ajuste.value = {
    aberto: true,
    data: d.data,
    registro: d.local,
    sugestao: d.rh?.horarios ?? null,
    motivo: d.tela.motivoDoAjuste ?? '',
  }
}

function aoSalvarAjuste(registro) {
  avisar(`Dia ${dataBR(registro.data)} ajustado. Comparando de novo com o RH…`)
  agendarRecarga()
}

// ------------------------------------------------------------------ vários dias de uma vez
async function abrirLote() {
  // já vêm marcados os tipos que o servidor sugere (os que não pedem um olhar dia a dia)
  lote.value = { aberto: true, tipos: tiposParaLote.value.filter((t) => t.sugerido).map((t) => t.tipo), aplicando: false }
  await nextTick()
  janelaLote.value?.querySelector('input, button')?.focus()
}

function fecharLote() {
  if (!lote.value.aplicando) lote.value.aberto = false
}

function alternarLote(tipo) {
  const tipos = lote.value.tipos
  lote.value.tipos = tipos.includes(tipo) ? tipos.filter((t) => t !== tipo) : [...tipos, tipo]
}

async function aplicarLote() {
  if (!lote.value.tipos.length) return
  lote.value.aplicando = true
  falhasLote.value = []
  const feito = await pedir(() => pontoApi.aceitarEmLote(lote.value.tipos))
  lote.value.aplicando = false
  if (!feito.ok) return
  lote.value.aberto = false
  const aceitas = feito.dados?.aceitas ?? 0
  const falhas = feito.dados?.falhas ?? []
  falhasLote.value = falhas
  avisar(`${aceitas === 1 ? '1 dia ficou igual' : `${aceitas} dias ficaram iguais`} ao RH${falhas.length ? `. ${falhas.length === 1 ? '1 dia não pôde' : `${falhas.length} dias não puderam`} ser copiado${falhas.length === 1 ? '' : 's'}: veja abaixo.` : '.'}`,
    falhas.length ? 'info' : 'ok')
  await carregar()
  // se os saldos não atualizarem agora, o aviso geral da tela oferece "Atualizar agora" (o que foi feito continua valendo)
  ponto.atualizarSaldos()
}

function aoTeclar(evento) {
  if (evento.key !== 'Escape') return
  if (remocao.value && !removendo.value) remocao.value = null
  else if (lote.value.aberto) fecharLote()
}
</script>

<template>
  <main class="pagina">
    <header>
      <h1 class="titulo-pagina">Conferir com o RH<template v-if="!meus && auth.pessoaEmTela"> · ponto de {{ auth.pessoaEmTela.nome }}</template></h1>
      <p class="subtitulo-pagina max-w-3xl">
        <template v-if="auth.podeEscrever">
          Envie o relatório de banco de horas que o RH emite. O sistema compara dia por dia com o ponto e mostra só
          o que está diferente, para você decidir.
        </template>
        <template v-else>
          O relatório de banco de horas do RH comparado dia por dia com o ponto: aqui aparece só o que está diferente.
        </template>
      </p>
    </header>

    <EstadoDaTela :carregando="carregando" :erro="erro" :manter="!!resumo" carregando-texto="Carregando a comparação com o RH…" @tentar="carregar()" />

    <template v-if="resumo">
      <!-- Passo 1: enviar o relatório -->
      <section
        class="cartao flex flex-wrap items-center gap-x-5 gap-y-4 p-5 transition-colors sm:p-6"
        :class="{ 'border-primaria! bg-primaria-suave!': arrastando }"
        aria-labelledby="titulo-passo-1"
        @dragover.prevent="arrastando = auth.podeEscrever"
        @dragleave.prevent="arrastando = false"
        @drop.prevent="aoSoltar"
      >
        <span
          class="grid size-10 shrink-0 place-items-center rounded-full text-lg font-extrabold"
          :class="relatorios.length ? 'bg-positivo-suave text-positivo' : 'bg-primaria-suave text-primaria'"
          aria-hidden="true"
        >
          <Icone v-if="relatorios.length" nome="certo" />
          <template v-else>1</template>
        </span>
        <div class="min-w-0 flex-[1_1_20rem]">
          <h2 id="titulo-passo-1" class="titulo-secao">{{ relatorios.length ? '1. Relatório do RH enviado' : '1. Envie o relatório do RH' }}</h2>
          <p v-if="relatorios.length" class="mt-0.5 text-[0.95rem] text-texto-2">
            {{ relatorios.length === 1 ? 'Um relatório enviado' : `${relatorios.length} relatórios enviados` }}.
            O mais recente vai de <b>{{ relatorios[0].tela.periodo }}</b>.
          </p>
          <p v-else-if="auth.podeEscrever" class="mt-0.5 text-[0.95rem] text-texto-2">
            É o PDF “Relatório de Banco de Horas” que o RH emite. Clique no botão ou arraste o arquivo para cá.
          </p>
          <p v-else class="mt-0.5 text-[0.95rem] text-texto-2">Ainda não há relatório do RH enviado.</p>
          <p v-if="auth.podeEscrever" class="mt-1 text-sm text-texto-3">
            O PDF não é guardado (ele tem CPF): o sistema fica só com as batidas e os saldos de cada dia.
          </p>
        </div>
        <template v-if="auth.podeEscrever">
          <button
            type="button"
            class="min-h-11 grow sm:grow-0"
            :class="relatorios.length ? 'botao-secundario' : 'botao-primario'"
            @click="campoArquivo?.click()"
          >
            <Icone nome="enviar" tamanho="18" />
            {{ relatorios.length ? 'Enviar outro relatório (PDF)' : 'Enviar relatório (PDF)' }}
          </button>
          <input ref="campoArquivo" type="file" accept="application/pdf,.pdf" multiple class="sr-only" aria-label="Arquivo PDF do relatório do RH" tabindex="-1" @change="aoEscolher" />
        </template>
        <ul v-if="enviando.length" class="flex w-full flex-col gap-2 border-t border-borda pt-4" aria-live="polite">
          <li v-for="(e, i) in enviando" :key="i" class="flex items-start gap-2.5 text-[0.95rem]">
            <Icone
              :nome="e.estado === 'erro' ? 'alerta' : e.estado === 'ok' ? 'certo' : 'atualizar'"
              class="mt-0.5"
              :class="e.estado === 'erro' ? 'text-negativo' : e.estado === 'ok' ? 'text-positivo' : 'animate-spin text-texto-3'"
            />
            <span class="min-w-0 break-words">
              <b>{{ e.nome }}</b>
              <span class="block" :class="e.estado === 'erro' ? 'text-negativo' : 'text-texto-2'">{{ e.mensagem }}</span>
            </span>
          </li>
        </ul>
      </section>

      <!-- Passo 2: o resultado da comparação -->
      <section class="cartao flex flex-col gap-4 p-5 sm:p-6" aria-labelledby="titulo-passo-2">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 id="titulo-passo-2" class="titulo-secao">{{ relatorios.length ? '2. Resultado da comparação' : '2. O sistema compara dia a dia' }}</h2>
          <button
            v-if="auth.podeEscrever && relatorios.length"
            type="button"
            class="botao-secundario min-h-11"
            :disabled="carregando || reconferindo"
            title="Refaz a comparação de todos os dias com os relatórios enviados"
            @click="reconferir"
          >
            <Icone nome="atualizar" tamanho="18" :class="{ 'animate-spin': reconferindo }" />
            {{ reconferindo ? 'Comparando…' : 'Comparar de novo' }}
          </button>
        </div>

        <p v-if="!relatorios.length" class="text-[0.95rem] text-texto-3">
          Assim que o relatório chega, o sistema compara cada dia dele com o ponto: as batidas, as folgas e feriados e o saldo do dia.
        </p>
        <template v-else>
          <p v-if="comparacao?.texto" class="text-base text-texto-2">{{ comparacao.texto }}</p>
          <div v-if="comparacao?.diasConferidos" class="grid grid-cols-2 gap-3 sm:gap-4">
            <div class="rounded-xl border border-borda px-4 py-3.5 sm:px-5">
              <h3 class="text-[0.95rem] font-semibold text-texto-3">Dias iguais</h3>
              <p class="mt-0.5 text-[1.35rem] leading-tight font-extrabold tracking-tight text-positivo sm:text-[1.65rem]">{{ comparacao.diasIguais }} de {{ comparacao.diasConferidos }}</p>
            </div>
            <div
              class="rounded-xl border px-4 py-3.5 sm:px-5"
              :class="comparacao.paraDecidir ? 'border-atencao-borda bg-atencao-suave text-atencao' : 'border-borda'"
            >
              <h3 class="text-[0.95rem] font-semibold" :class="{ 'text-texto-3': !comparacao.paraDecidir }">Dias diferentes</h3>
              <p v-if="comparacao.paraDecidir" class="mt-0.5 text-[1.35rem] leading-tight font-extrabold tracking-tight sm:text-[1.65rem]">{{ comparacao.paraDecidir }} para decidir</p>
              <p v-else class="mt-0.5 flex items-center gap-2 text-[1.35rem] leading-tight font-extrabold tracking-tight text-positivo sm:text-[1.65rem]"><Icone nome="certo" tamanho="24" /> Nenhum para decidir</p>
              <p v-if="comparacao.mantidos" class="text-sm">
                e {{ comparacao.mantidos }} {{ comparacao.mantidos === 1 ? 'mantido como está' : 'mantidos como estão' }} no sistema
              </p>
            </div>
          </div>

          <!-- Relatórios enviados, com o saldo de cada lado -->
          <div>
            <h3 class="text-base font-extrabold">{{ relatorios.length === 1 ? 'Relatório enviado' : 'Relatórios enviados' }}</h3>
            <ul class="mt-1 divide-y divide-borda border-t border-borda">
              <li v-for="r in relatorios" :key="r.id" class="flex flex-col gap-2.5 py-3.5">
                <div class="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                  <div class="min-w-0 flex-[1_1_16rem]">
                    <p class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                      <b class="text-base">{{ r.tela.periodo }}</b>
                      <span v-if="r.status !== 'CONCLUIDO'" class="selo" :class="seloDoTom(r.tela.tom)" role="status">{{ r.tela.situacao }}</span>
                    </p>
                    <p class="text-sm break-words text-texto-3">{{ r.nomeArquivo }} · {{ r.tela.emitido }} · enviado por {{ r.enviadoPor }} em {{ momento(r.enviadoEm) }}</p>
                    <p v-if="r.status === 'ERRO' && r.mensagem" class="mt-1 text-sm font-semibold text-negativo">{{ r.mensagem }}</p>
                  </div>
                  <button v-if="auth.podeEscrever" type="button" class="botao-linha" :aria-label="`Remover o relatório de ${r.tela.periodo}`" @click="pedirRemocao(r)">
                    <Icone nome="lixeira" tamanho="18" /> Remover
                  </button>
                </div>
                <dl v-if="r.tela.saldoRh" class="grid grid-cols-2 gap-x-6 gap-y-2 text-[0.95rem] sm:flex sm:flex-wrap sm:gap-x-10">
                  <div>
                    <dt class="text-sm text-texto-3">Saldo no RH</dt>
                    <dd class="font-bold" :title="exato(r.saldoRhSegundos)">{{ r.tela.saldoRh }}</dd>
                  </div>
                  <div>
                    <dt class="text-sm text-texto-3">Saldo no sistema</dt>
                    <dd class="font-bold" :title="exato(r.saldoLocalSegundos)">{{ r.tela.saldoSistema }}</dd>
                  </div>
                  <div v-if="r.tela.diferenca">
                    <dt class="text-sm text-texto-3">Comparando os dois</dt>
                    <dd class="font-bold" :class="r.tela.saldosIguais ? 'text-positivo' : 'text-negativo'">{{ r.tela.diferenca }}</dd>
                  </div>
                  <div v-if="r.tela.diasIguais">
                    <dt class="text-sm text-texto-3">Dia a dia</dt>
                    <dd class="font-bold">{{ r.tela.diasIguais }}</dd>
                  </div>
                </dl>
                <p v-if="r.tela.foraDoSaldo" class="text-sm text-atencao">{{ r.tela.foraDoSaldo }}</p>
              </li>
            </ul>
          </div>
        </template>
      </section>

      <!-- Passo 3: decidir cada diferença -->
      <section class="flex flex-col gap-4" aria-labelledby="titulo-passo-3">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 id="titulo-passo-3" class="titulo-secao">{{ relatorios.length ? '3. Decida cada dia diferente' : '3. Você decide cada diferença' }}</h2>
          <button
            v-if="auth.podeEscrever && filtro === 'PENDENTE' && tiposParaLote.length"
            type="button"
            class="botao-escuro min-h-11"
            @click="abrirLote"
          >Usar o do RH em vários dias</button>
        </div>

        <p v-if="!relatorios.length" class="cartao p-5 text-[0.95rem] text-texto-3 sm:p-6">
          Cada dia diferente aparece aqui dizendo o que o RH tem e o que o sistema tem, com dois botões:
          <b class="text-texto-2">Usar o do RH</b> ou <b class="text-texto-2">Manter o meu</b>. Nada muda sem a sua decisão.
        </p>

        <template v-else>
          <!-- Filtros -->
          <div class="flex flex-wrap items-center gap-2" role="group" aria-label="Mostrar">
            <button
              v-for="f in filtros"
              :key="f.valor"
              type="button"
              class="pilula"
              :class="{ 'pilula-ativa': filtroAtivo(f.valor) }"
              :aria-pressed="filtroAtivo(f.valor)"
              @click="filtro = f.valor"
            >{{ f.rotulo }}<template v-if="quantas(f.valor) !== null"> ({{ quantas(f.valor) }})</template></button>
          </div>
          <div v-if="vendoDecididas" class="flex flex-wrap items-center gap-2" role="group" aria-label="Como foram decididas">
            <button
              v-for="f in FILTROS_DECIDIDAS"
              :key="f.valor"
              type="button"
              class="pilula"
              :class="{ 'pilula-ativa': filtro === f.valor }"
              :aria-pressed="filtro === f.valor"
              @click="filtro = f.valor"
            >{{ f.rotulo }}<template v-if="f.valor !== 'DECIDIDAS' && quantas(f.valor) !== null"> ({{ quantas(f.valor) }})</template></button>
          </div>
          <button
            v-if="filtro === 'PENDENTE' && tiposComPendencia.length > 1"
            type="button"
            class="botao-linha self-start sm:hidden"
            :aria-expanded="tiposAbertos"
            aria-controls="filtro-por-tipo"
            @click="tiposAbertos = !tiposAbertos"
          >
            {{ tipoEmTela ? `Só: ${nomeDoTipo(tipoEmTela)} (${tipoEmTela.pendentes})` : 'Filtrar por tipo de diferença' }}
            <Icone nome="abaixo" tamanho="16" class="transition-transform" :class="{ 'rotate-180': tiposAbertos }" />
          </button>
          <div
            v-if="filtro === 'PENDENTE' && tiposComPendencia.length > 1"
            id="filtro-por-tipo"
            class="flex-wrap items-center gap-2 sm:flex"
            :class="tiposAbertos ? 'flex' : 'hidden'"
            role="group"
            aria-label="Tipo de diferença"
          >
            <button type="button" class="pilula" :class="{ 'pilula-ativa': !filtroTipo }" :aria-pressed="!filtroTipo" @click="filtroTipo = null">Todos os tipos</button>
            <button
              v-for="t in tiposComPendencia"
              :key="t.tipo"
              type="button"
              class="pilula"
              :class="{ 'pilula-ativa': filtroTipo === t.tipo }"
              :aria-pressed="filtroTipo === t.tipo"
              :title="t.explicacao"
              @click="filtroTipo = filtroTipo === t.tipo ? null : t.tipo"
            >{{ nomeDoTipo(t) }} ({{ t.pendentes }})</button>
          </div>

          <!-- Dias que o "vários de uma vez" não conseguiu copiar -->
          <div v-if="falhasLote.length" class="aviso-atencao flex flex-col gap-2" role="status">
            <p class="text-[0.95rem] font-bold">Estes dias não puderam ficar iguais ao RH e continuam para decidir:</p>
            <ul class="flex flex-col gap-1 text-[0.95rem]">
              <li v-for="f in falhasLote" :key="f.data"><b>{{ dataBR(f.data) }}:</b> {{ f.mensagem }}</li>
            </ul>
            <button type="button" class="botao-secundario self-start px-3! py-1.5!" @click="falhasLote = []">Entendi</button>
          </div>

          <p v-if="notaDoUsarRh" class="text-[0.95rem] text-texto-3">{{ notaDoUsarRh }}</p>

          <p v-if="!listaCarregada" class="py-8 text-center text-[0.95rem] text-texto-3" role="status">Carregando as diferenças…</p>
          <div
            v-else-if="!filtradas.length && filtro === 'PENDENTE' && !filtroTipo"
            class="flex items-center gap-3 rounded-2xl border border-positivo-borda bg-positivo-suave p-5 text-positivo"
          >
            <Icone nome="certo" tamanho="26" />
            <div>
              <h3 class="text-base font-bold">Nada para decidir</h3>
              <p class="text-[0.95rem]">Nenhum dia esperando decisão.</p>
            </div>
          </div>
          <p v-else-if="!filtradas.length" class="cartao p-6 text-center text-[0.95rem] text-texto-3">Nenhuma diferença neste filtro.</p>

          <ul v-if="visiveis.length" class="flex flex-col gap-4">
            <li v-for="d in visiveis" :key="d.id">
              <!-- Para decidir: cartão grande, com os dois lados e os botões -->
              <article v-if="d.status === 'PENDENTE'" class="cartao flex flex-col gap-4 p-5 sm:p-6" :aria-labelledby="`dia-${d.id}`">
                <div>
                  <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                    <h3 :id="`dia-${d.id}`" class="text-lg font-extrabold">{{ d.tela.dia }}</h3>
                    <span class="selo selo-neutro">{{ d.tela.tipo }}</span>
                  </div>
                  <p class="mt-1.5 text-base text-texto-2">{{ d.tela.frase }}</p>
                  <p v-if="d.tela.impacto" class="mt-1 text-[0.95rem] text-texto-3">{{ d.tela.impacto }}</p>
                </div>

                <div class="grid gap-3 sm:grid-cols-2">
                  <div
                    v-for="lado in lados(d)"
                    :key="lado.chave"
                    class="rounded-xl border px-4 py-3.5"
                    :class="lado.doRh ? 'border-primaria-borda bg-primaria-suave' : 'border-borda'"
                  >
                    <h4 class="text-sm font-bold" :class="lado.doRh ? 'text-primaria' : 'text-texto-3'">{{ lado.nome }}</h4>
                    <p v-if="lado.titulo" class="mt-1 text-[1.05rem] font-bold">{{ lado.titulo }}</p>
                    <p v-if="lado.horarios.length" class="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[1.05rem] font-bold">
                      <template v-for="(h, i) in lado.horarios" :key="i">
                        <span v-if="i" class="font-normal text-texto-4" aria-hidden="true">·</span>
                        <span :class="classeDaHora(h)" :title="h.exato">{{ h.texto }}</span>
                      </template>
                    </p>
                    <p v-if="lado.saldo" class="mt-0.5 text-[0.95rem]">
                      <span class="font-semibold" :class="textoDoTom(lado.tom)" :title="lado.saldoExato">{{ lado.saldo }}</span>
                      <span v-if="lado.trabalhado" class="text-texto-3"> · trabalhou {{ lado.trabalhado }}</span>
                    </p>
                  </div>
                </div>

                <template v-if="auth.podeEscrever">
                  <div v-if="d.tela.acoes.usarRh || d.tela.acoes.manter || d.tela.acoes.ajustar || d.tela.acoes.folgas" class="flex flex-wrap gap-2.5">
                    <button
                      v-if="d.tela.acoes.usarRh"
                      type="button"
                      class="botao-primario min-h-12 grow px-5! text-base! sm:grow-0"
                      :disabled="!!ocupado"
                      @click="usarRh(d)"
                    >{{ ocupado?.id === d.id && ocupado.acao === 'usarRh' ? 'Aplicando…' : 'Usar o do RH' }}</button>
                    <button
                      v-if="d.tela.acoes.manter"
                      type="button"
                      class="botao-secundario min-h-12 grow px-5! text-base! sm:grow-0"
                      :disabled="!!ocupado"
                      :aria-expanded="mantendo?.id === d.id"
                      @click="iniciarManter(d)"
                    >Manter o meu</button>
                    <button
                      v-if="d.tela.acoes.ajustar"
                      type="button"
                      class="botao-secundario min-h-12 grow px-5! text-base! sm:grow-0"
                      :disabled="!!ocupado"
                      @click="ajustarManual(d)"
                    ><Icone nome="lapis" tamanho="18" /> Ajustar as batidas à mão</button>
                    <RouterLink v-if="d.tela.acoes.folgas" :to="{ name: 'ausencias' }" class="botao-secundario min-h-12 grow px-5! text-base! sm:grow-0">
                      <Icone nome="sol" tamanho="18" /> Abrir Folgas e feriados
                    </RouterLink>
                  </div>

                  <!-- Manter o meu: passo de confirmação, com o motivo (opcional) -->
                  <form v-if="mantendo?.id === d.id" class="flex flex-col gap-3 rounded-xl bg-superficie-2 p-4" @submit.prevent="confirmarManter(d)">
                    <div>
                      <label class="rotulo" :for="`obs-${d.id}`">Quer anotar o motivo? (opcional)</label>
                      <input :id="`obs-${d.id}`" v-model="mantendo.observacao" type="text" maxlength="300" class="campo mt-1" placeholder="Ex.: o RH vai corrigir o relatório" />
                    </div>
                    <p class="text-sm text-texto-3">
                      O dia fica como está no sistema e sai da lista “Para decidir”. Se o dia ou o relatório mudar, a diferença volta para você decidir.
                    </p>
                    <div class="flex flex-wrap gap-2">
                      <button type="submit" class="botao-primario min-h-11">Confirmar: manter o meu</button>
                      <button type="button" class="botao-secundario min-h-11" @click="mantendo = null">Cancelar</button>
                    </div>
                  </form>

                  <p v-if="d.tela.porQueNaoUsarRh" class="aviso-atencao text-[0.95rem]">
                    <b>Neste dia não dá para usar o do RH.</b> {{ d.tela.porQueNaoUsarRh }}
                  </p>
                  <p v-else-if="d.tela.aoUsarRh && d.tela.acoes.folgas" class="text-sm text-texto-3">{{ d.tela.aoUsarRh }}</p>
                </template>
              </article>

              <!-- Já decidida: discreta, com "Reabrir" -->
              <article v-else class="cartao flex flex-col gap-2 px-5 py-4 sm:px-6" :aria-labelledby="`dia-${d.id}`">
                <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
                  <h3 :id="`dia-${d.id}`" class="text-base font-bold">{{ d.tela.dia }}</h3>
                  <span class="selo" :class="seloDoTom(d.tela.tom)">{{ d.tela.situacao }}</span>
                  <span class="ml-auto flex flex-wrap gap-2">
                    <button
                      type="button"
                      class="botao-linha gap-1!"
                      :aria-expanded="abertas.has(d.id)"
                      :aria-controls="`lados-${d.id}`"
                      :aria-label="`${abertas.has(d.id) ? 'Fechar' : 'Ver'} os detalhes de ${d.tela.dia}`"
                      @click="alternarDetalhe(d)"
                    >Detalhes <Icone nome="abaixo" tamanho="16" class="transition-transform" :class="{ 'rotate-180': abertas.has(d.id) }" /></button>
                    <button v-if="auth.podeEscrever && d.tela.acoes.reabrir" type="button" class="botao-linha" :disabled="!!ocupado" @click="reabrir(d)">
                      {{ ocupado?.id === d.id ? 'Reabrindo…' : 'Reabrir' }}
                    </button>
                  </span>
                </div>
                <p class="text-[0.95rem] text-texto-2"><span class="text-texto-3">{{ d.tela.tipo }}.</span> {{ d.tela.frase }}</p>
                <p v-if="d.resolvidaEm" class="text-sm text-texto-3">
                  Decidido {{ quemDecidiu(d) }} em {{ momento(d.resolvidaEm) }}<template v-if="d.observacao"> · Observação: “{{ d.observacao }}”</template>
                </p>
                <div v-if="abertas.has(d.id)" :id="`lados-${d.id}`" class="mt-1 grid gap-3 sm:grid-cols-2">
                  <div v-for="lado in lados(d)" :key="lado.chave" class="rounded-xl border border-borda bg-superficie-2 px-4 py-3">
                    <h4 class="text-sm font-bold text-texto-3">{{ lado.nome }}, hoje</h4>
                    <p v-if="lado.titulo" class="mt-0.5 font-bold">{{ lado.titulo }}</p>
                    <p v-if="lado.horarios.length" class="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 font-bold">
                      <template v-for="(h, i) in lado.horarios" :key="i">
                        <span v-if="i" class="font-normal text-texto-4" aria-hidden="true">·</span>
                        <span :class="classeDaHora(h)" :title="h.exato">{{ h.texto }}</span>
                      </template>
                    </p>
                    <p v-if="lado.saldo" class="text-[0.95rem]">
                      <span class="font-semibold" :class="textoDoTom(lado.tom)" :title="lado.saldoExato">{{ lado.saldo }}</span>
                      <span v-if="lado.trabalhado" class="text-texto-3"> · trabalhou {{ lado.trabalhado }}</span>
                    </p>
                  </div>
                </div>
              </article>
            </li>
          </ul>

          <div v-if="filtradas.length > visiveis.length" class="flex flex-wrap items-center justify-between gap-3">
            <span class="text-[0.95rem] text-texto-3">Mostrando {{ visiveis.length }} dos {{ filtradas.length }} dias</span>
            <button type="button" class="botao-linha" @click="limite += PRIMEIRAS">Mostrar mais dias</button>
          </div>

          <p v-if="temDiferentes || temSegundos" class="text-sm text-texto-3">
            <template v-if="temDiferentes">
              <span class="rounded-md bg-atencao-suave px-1.5 font-bold text-atencao">12:00</span> é uma batida que o outro lado não tem.
            </template>
            <template v-if="temSegundos">
              <span class="font-bold underline decoration-dotted underline-offset-4">08:05:53</span> é a mesma batida com segundos diferentes
              (o comprovante em PDF costuma marcar 1 segundo depois do RH).
            </template>
          </p>

          <!-- O que já foi decidido fica à parte -->
          <div v-if="filtro === 'PENDENTE' && quantas('DECIDIDAS')" class="cartao flex flex-wrap items-center justify-between gap-3 px-5 py-4 sm:px-6">
            <span class="text-[0.95rem] text-texto-2">
              <b>{{ quantas('DECIDIDAS') === 1 ? '1 dia' : `${quantas('DECIDIDAS')} dias` }}</b> já {{ quantas('DECIDIDAS') === 1 ? 'decidido' : 'decididos' }}
            </span>
            <button type="button" class="botao-secundario min-h-11" @click="filtro = 'DECIDIDAS'">Ver o que já foi decidido</button>
          </div>
        </template>
      </section>
    </template>

    <ModalAjusteBatidas
      v-if="auth.podeEscrever"
      v-model="ajuste.aberto"
      :data="ajuste.data"
      :registro="ajuste.registro"
      :sugestao-rh="ajuste.sugestao"
      :motivo-sugerido="ajuste.motivo"
      @salvo="aoSalvarAjuste"
    />

    <Teleport to="body">
      <!-- Usar o do RH em vários dias de uma vez -->
      <div v-if="lote.aberto" class="janela-fundo" @mousedown.self="fecharLote">
        <section ref="janelaLote" role="dialog" aria-modal="true" aria-labelledby="titulo-lote" class="janela">
          <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
            <div>
              <h2 id="titulo-lote" class="text-xl font-extrabold tracking-tight">Usar o do RH em vários dias</h2>
              <p class="mt-1 text-[0.95rem] text-texto-3">Marque os tipos de diferença que você quer resolver de uma vez.</p>
            </div>
            <button type="button" class="-mt-1 -mr-2 grid size-11 shrink-0 place-items-center rounded-xl text-texto-3 hover:bg-neutro hover:text-texto" aria-label="Fechar" @click="fecharLote">
              <Icone nome="fechar" />
            </button>
          </header>
          <div class="flex flex-col gap-4 px-5 py-5">
            <ul class="flex flex-col gap-2">
              <li v-for="t in tiposParaLote" :key="t.tipo">
                <label class="flex cursor-pointer items-start gap-3 rounded-xl border border-borda px-3.5 py-3 hover:bg-neutro">
                  <input type="checkbox" class="mt-0.5 size-5 shrink-0 accent-primaria" :checked="lote.tipos.includes(t.tipo)" @change="alternarLote(t.tipo)" />
                  <span class="min-w-0">
                    <span class="font-bold">{{ nomeDoTipo(t) }}</span>
                    <span class="text-texto-3"> · {{ (t.aceitaveis ?? t.pendentes) === 1 ? '1 dia' : `${t.aceitaveis ?? t.pendentes} dias` }}</span>
                    <span v-if="t.explicacao" class="block text-sm text-texto-3">{{ t.explicacao }}</span>
                  </span>
                </label>
              </li>
            </ul>
            <p class="text-sm text-texto-3">
              Cada dia marcado fica igual ao RH, e a troca fica no histórico do dia. Batida com comprovante (PDF) nunca é apagada:
              só os segundos são alinhados. Os dias que não puderem ser copiados continuam na lista, para você decidir um a um.
            </p>
            <div class="flex flex-wrap justify-end gap-2">
              <button type="button" class="botao-secundario min-h-11" :disabled="lote.aplicando" @click="fecharLote">Cancelar</button>
              <button type="button" class="botao-primario min-h-11" :disabled="lote.aplicando || !lote.tipos.length" @click="aplicarLote">
                {{ lote.aplicando ? 'Aplicando…' : 'Usar o do RH nos dias marcados' }}
              </button>
            </div>
          </div>
        </section>
      </div>

      <!-- Confirmação antes de remover um relatório -->
      <div v-if="remocao" class="janela-fundo" @mousedown.self="!removendo && (remocao = null)">
        <section ref="janelaRemocao" role="alertdialog" aria-modal="true" aria-labelledby="titulo-remocao" aria-describedby="texto-remocao" class="janela max-w-md px-5 py-5">
          <h2 id="titulo-remocao" class="text-xl font-extrabold tracking-tight">Remover este relatório?</h2>
          <p id="texto-remocao" class="mt-2 text-[0.95rem] text-texto-2">
            <b class="break-words">{{ remocao.nomeArquivo }}</b> ({{ remocao.tela.periodo }}). As diferenças dele que ainda esperam decisão saem da lista.
            O que você já resolveu usando o do RH continua no sistema.
          </p>
          <div class="mt-5 flex flex-wrap justify-end gap-2">
            <button type="button" class="botao-secundario min-h-11" :disabled="removendo" @click="remocao = null">Cancelar</button>
            <button type="button" class="botao-perigo min-h-11" :disabled="removendo" @click="confirmarRemocao">{{ removendo ? 'Removendo…' : 'Remover relatório' }}</button>
          </div>
        </section>
      </div>
    </Teleport>
  </main>
</template>
