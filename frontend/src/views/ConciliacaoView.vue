<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import ModalAjusteBatidas from '@/components/ModalAjusteBatidas.vue'
import { dataBR, diaSemanaCurto, formatarDuracao, formatarSaldo, paraSegundos } from '@/utils/tempo'

/**
 * Conciliação com o relatório de banco de horas do RH: envia o PDF, o servidor compara dia a dia
 * em segundo plano e aqui cada diferença aparece lado a lado (conferência × RH) para decidir:
 * aceitar os dados do RH, manter os dados locais ou ajustar à mão. Nada muda sozinho.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const { ultimaConciliacao, ultimoEvento } = storeToRefs(ponto)

const PADRAO_LOTE = ['SOMENTE_RH', 'TIPO_DIA', 'DIFERENCA_SEGUNDOS']
const DICAS_TIPO = {
  TIPO_DIA: 'Feriado, férias ou folga no RH que aqui é dia útil (ou o contrário)',
  SOMENTE_RH: 'Dia com batidas no RH e sem registro aqui (ex.: antes de usar a conferência)',
  SOMENTE_LOCAL: 'Batidas aqui que o RH não tem',
  BATIDA_FALTANDO: 'O RH tem batida que aqui não tem (ex.: esquecida e corrigida pelo RH)',
  BATIDA_SOBRANDO: 'Batida aqui que o RH não tem',
  HORARIO_DIFERENTE: 'Mesma batida com mais de 1 minuto de diferença',
  SALDO: 'Mesmas batidas e saldo diferente (regra de cálculo)',
  DIFERENCA_SEGUNDOS: 'Segundos diferentes (o PDF marca 1 s depois) que mudam o saldo',
}
const ROTULO_STATUS = { PENDENTE: 'Pendente', ACEITO_RH: 'Dados do RH aceitos', MANTIDO_LOCAL: 'Mantido local', RESOLVIDA: 'Resolvida' }
const ROTULO_TIPO_DIA = { UTIL: 'dia útil', FIM_DE_SEMANA: 'fim de semana', FERIADO: 'feriado', AUSENCIA: 'ausência' }
const MESMA_BATIDA = 60

// ------------------------------------------------------------------ estado
const resumo = ref(null)
const divergencias = ref([])
const status = ref('PENDENTE')
const filtroTipo = ref(null)
const limite = ref(40)
const carregando = ref(false)
const erro = ref('')
const aviso = ref(null)
const ocupado = ref(new Set()) // ids com ação em andamento
const mantendo = ref(null) // { id, observacao }
const loteTipos = ref([...PADRAO_LOTE])
const aceitandoLote = ref(false)
const falhasLote = ref([])
const enviando = ref([]) // [{ nome, estado: 'enviando'|'ok'|'erro', mensagem }]
const arrastando = ref(false)
const campoArquivo = ref(null)
const ajuste = ref({ aberto: false, data: null, registro: null, sugestao: null })
let timerRecarga = null
let timerAviso = null

async function carregar() {
  carregando.value = true
  erro.value = ''
  try {
    const [r, d] = await Promise.all([pontoApi.resumoConciliacao(), pontoApi.divergencias(status.value)])
    resumo.value = r
    divergencias.value = d
  } catch (e) {
    erro.value = e.message
  } finally {
    carregando.value = false
  }
}

onMounted(carregar)
onBeforeUnmount(() => {
  clearTimeout(timerRecarga)
  clearTimeout(timerAviso)
})
watch(status, () => {
  limite.value = 40
  carregar()
})

/** Relatório conferido, divergência resolvida ou um dia mudou (PDF, ajuste): recarrega. */
function agendarRecarga() {
  clearTimeout(timerRecarga)
  timerRecarga = setTimeout(carregar, 600)
}
watch(ultimaConciliacao, (e) => e && agendarRecarga())
watch(ultimoEvento, (e) => e?.tipo === 'jornada-atualizada' && agendarRecarga())

function mostrarAviso(texto, tipo = 'ok') {
  aviso.value = { texto, tipo }
  clearTimeout(timerAviso)
  timerAviso = setTimeout(() => (aviso.value = null), 6000)
}

// ------------------------------------------------------------------ envio
async function enviarArquivos(arquivos) {
  const pdfs = [...arquivos].filter((a) => a.name.toLowerCase().endsWith('.pdf'))
  if (!pdfs.length) {
    mostrarAviso('Selecione o PDF do relatório de banco de horas.', 'erro')
    return
  }
  for (const arquivo of pdfs) {
    const item = { nome: arquivo.name, estado: 'enviando', mensagem: '' }
    enviando.value = [item, ...enviando.value].slice(0, 8)
    try {
      const r = await pontoApi.enviarRelatorioRh(arquivo)
      Object.assign(item, { estado: 'ok', mensagem: `Período ${dataBR(r.periodoInicio)} a ${dataBR(r.ultimoDiaConferido)} · ${r.diasLidos} dias · enviado` })
    } catch (e) {
      Object.assign(item, { estado: 'erro', mensagem: e.message })
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

async function reconferir() {
  carregando.value = true
  try {
    resumo.value = await pontoApi.reconferir()
    divergencias.value = await pontoApi.divergencias(status.value)
    mostrarAviso('Conferência refeita com todos os relatórios enviados.')
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  } finally {
    carregando.value = false
  }
}

async function excluirRelatorio(r) {
  if (!window.confirm(`Remover o relatório "${r.nomeArquivo}"? As divergências dele somem; o que já foi aceito continua na conferência.`)) return
  try {
    await pontoApi.excluirRelatorioRh(r.id)
    mostrarAviso('Relatório removido.')
    carregar()
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  }
}

// ------------------------------------------------------------------ listas
const tiposComPendencia = computed(() => (resumo.value?.tipos ?? []).filter((t) => t.pendentes > 0))

/** Mais recentes primeiro: é onde as decisões importam para o ciclo atual. */
const filtradas = computed(() =>
  divergencias.value
    .filter((d) => !filtroTipo.value || d.tipo === filtroTipo.value)
    .slice()
    .sort((a, b) => b.data.localeCompare(a.data)))

const visiveis = computed(() => filtradas.value.slice(0, limite.value))

const qtdLote = computed(() =>
  divergencias.value.filter((d) => d.status === 'PENDENTE' && d.aceitavel && loteTipos.value.includes(d.tipo)).length)

const perto = (a, b) => Math.abs(paraSegundos(a) - paraSegundos(b)) <= MESMA_BATIDA

function lados(d) {
  const local = (d.local?.batidas ?? []).filter((b) => b.real).map((b) => b.real)
  const rh = d.rh?.horarios ?? []
  const iguais = (a, lista) => lista.some((b) => a === b)
  return {
    local: local.map((h) => ({ h, estado: iguais(h, rh) ? 'igual' : rh.some((r) => perto(h, r)) ? 'segundos' : 'diferente' })),
    rh: rh.map((h) => ({ h, estado: iguais(h, local) ? 'igual' : local.some((l) => perto(h, l)) ? 'segundos' : 'diferente' })),
  }
}

const classeHora = {
  igual: 'text-tinta',
  segundos: 'text-amber-800 underline decoration-dotted underline-offset-4',
  diferente: 'bg-carimbo/10 font-semibold text-carimbo',
}

function classeSaldo(seg) {
  if (seg === null || seg === undefined) return 'text-tinta-apagada'
  return seg > 0 ? 'text-credito' : seg < 0 ? 'text-carimbo' : 'text-tinta'
}

const formatarMomento = (iso) =>
  new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })

// ------------------------------------------------------------------ ações
async function executar(d, acao, mensagem) {
  ocupado.value = new Set(ocupado.value).add(d.id)
  try {
    const atualizada = await acao()
    mostrarAviso(typeof mensagem === 'function' ? mensagem(atualizada) : mensagem)
    await carregar()
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  } finally {
    const resto = new Set(ocupado.value)
    resto.delete(d.id)
    ocupado.value = resto
  }
}

function aceitar(d) {
  executar(d, () => pontoApi.aceitarDivergencia(d.id), (a) =>
    a && a.status === 'PENDENTE'
      ? `${dataBR(d.data)}: dados do RH aplicados, mas ainda há diferença (${a.tipoRotulo.toLowerCase()}).`
      : `${dataBR(d.data)}: conferência igual ao RH.`)
}

function iniciarManter(d) {
  mantendo.value = { id: d.id, observacao: '' }
}

function confirmarManter(d) {
  const obs = mantendo.value?.observacao?.trim() || null
  mantendo.value = null
  executar(d, () => pontoApi.manterDivergencia(d.id, obs), `${dataBR(d.data)}: mantidos os dados da conferência.`)
}

function reabrir(d) {
  executar(d, () => pontoApi.reabrirDivergencia(d.id), `${dataBR(d.data)}: divergência reaberta.`)
}

function ajustarManual(d) {
  ajuste.value = {
    aberto: true,
    data: d.data,
    registro: d.local,
    sugestao: d.rh?.horarios ?? null,
    motivo: `Conforme relatório do RH emitido em ${d.rh?.relatorioEmitidoEm ? dataBR(d.rh.relatorioEmitidoEm) : '—'}`,
  }
}

function aoSalvarAjuste(registro) {
  mostrarAviso(`${dataBR(registro.data)} ajustado · saldo ${formatarSaldo(registro.saldoDiarioSegundos)}. Conferindo com o RH…`)
  agendarRecarga()
}

async function aceitarLote() {
  if (!qtdLote.value) return
  aceitandoLote.value = true
  falhasLote.value = []
  try {
    const r = await pontoApi.aceitarEmLote(loteTipos.value)
    falhasLote.value = r.falhas
    mostrarAviso(`${r.aceitas} dia(s) atualizados com os dados do RH${r.falhas.length ? ` · ${r.falhas.length} não puderam ser aceitos` : ''}.`,
      r.falhas.length ? 'info' : 'ok')
    await carregar()
    ponto.atualizarSaldos()
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  } finally {
    aceitandoLote.value = false
  }
}

function alternarLote(tipo) {
  loteTipos.value = loteTipos.value.includes(tipo) ? loteTipos.value.filter((t) => t !== tipo) : [...loteTipos.value, tipo]
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">Auditoria cruzada · relatório de banco de horas do RH</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Conciliação com o RH</h1>
        <p v-if="!auth.vendoOsProprios" class="mt-1 font-sans text-lg font-semibold">{{ auth.pessoaEmTela?.nome }}</p>
        <p class="mt-2 max-w-2xl text-sm text-tinta-suave">
          Envie o PDF do relatório do RH: cada dia é comparado com a conferência (batidas com segundos, tipo do dia e saldo).
          Nada é alterado sem a sua decisão.
        </p>
      </div>
      <button v-if="auth.podeEscrever" type="button" class="botao-secundario" :disabled="carregando || !resumo?.relatorios.length" @click="reconferir">Reconferir tudo</button>
    </header>

    <!-- Envio -->
    <section
      v-if="auth.podeEscrever"
      class="mt-6 rounded-[3px] border-2 border-dashed px-5 py-6 text-center transition"
      :class="arrastando ? 'border-tinta bg-papel-escuro' : 'border-linha bg-cartao/60'"
      aria-label="Enviar relatório do RH"
      @dragover.prevent="arrastando = true"
      @dragleave.prevent="arrastando = false"
      @drop.prevent="aoSoltar"
    >
      <p class="font-sans text-lg font-bold">Arraste aqui o PDF do relatório de banco de horas</p>
      <p class="mt-1 text-sm text-tinta-suave">
        ou
        <button type="button" class="font-semibold text-tinta underline underline-offset-4" @click="campoArquivo?.click()">escolha o arquivo</button>.
        O PDF não é guardado (tem CPF): ficam só as batidas e os saldos de cada dia.
      </p>
      <input ref="campoArquivo" type="file" accept="application/pdf,.pdf" multiple class="sr-only" @change="aoEscolher" />
      <ul v-if="enviando.length" class="mx-auto mt-4 max-w-2xl space-y-1 text-left text-sm">
        <li v-for="(e, i) in enviando" :key="i" class="flex gap-2">
          <span :class="e.estado === 'erro' ? 'text-carimbo' : e.estado === 'ok' ? 'text-credito' : 'text-tinta-suave'">
            {{ e.estado === 'erro' ? '✕' : e.estado === 'ok' ? '✓' : '…' }}
          </span>
          <span><b>{{ e.nome }}</b> <span class="text-tinta-suave">{{ e.mensagem }}</span></span>
        </li>
      </ul>
    </section>

    <p v-if="erro" role="alert" class="cartao mt-4 border-carimbo/50 px-5 py-3 text-sm text-carimbo">{{ erro }}</p>

    <!-- Relatórios -->
    <section v-if="resumo?.relatorios.length" class="cartao mt-6 overflow-hidden" aria-label="Relatórios do RH enviados">
      <h2 class="rotulo border-b border-linha px-5 py-3">Relatórios enviados · comparativo de saldo nos dias conferidos</h2>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[52rem] border-collapse text-sm">
          <thead>
            <tr class="border-b border-linha text-left">
              <th class="rotulo px-4 py-2">Relatório</th>
              <th class="rotulo px-2 py-2">Período conferido</th>
              <th class="rotulo px-2 py-2 text-right">Saldo RH</th>
              <th class="rotulo px-2 py-2 text-right">Conferência</th>
              <th class="rotulo px-2 py-2 text-right">Diferença</th>
              <th class="rotulo px-2 py-2 text-right">Pendências</th>
              <th v-if="auth.podeEscrever" class="w-10" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in resumo.relatorios" :key="r.id" class="border-b border-linha/70 last:border-0">
              <td class="px-4 py-2">
                <span class="font-semibold">{{ r.nomeArquivo }}</span>
                <span class="block text-xs text-tinta-suave">
                  emitido em {{ dataBR(r.emitidoEm) }} às {{ r.emitidoEm.slice(11, 16) }}
                  <template v-if="r.status !== 'CONCLUIDO'"> · <b :class="r.status === 'ERRO' ? 'text-carimbo' : ''">{{ r.status === 'ERRO' ? r.mensagem : 'conferindo…' }}</b></template>
                </span>
              </td>
              <td class="carimbo px-2 py-2 whitespace-nowrap">{{ dataBR(r.periodoInicio) }} → {{ dataBR(r.ultimoDiaConferido) }}<span class="block font-sans text-xs text-tinta-suave">{{ r.diasConferidos }} dias</span></td>
              <td class="carimbo px-2 py-2 text-right font-semibold" :class="classeSaldo(r.saldoRhSegundos)">{{ formatarSaldo(r.saldoRhSegundos) }}</td>
              <td class="carimbo px-2 py-2 text-right font-semibold" :class="classeSaldo(r.saldoLocalSegundos)">
                {{ formatarSaldo(r.saldoLocalSegundos) }}
                <span v-if="r.diasEmAbertoLocal" class="block font-sans text-xs font-normal text-carimbo">{{ r.diasEmAbertoLocal }} incompleto(s)</span>
              </td>
              <td class="carimbo px-2 py-2 text-right">
                <span v-if="r.saldoLocalSegundos === r.saldoRhSegundos" class="font-sans text-xs font-bold tracking-wide text-credito uppercase">igual ✓</span>
                <span v-else class="font-semibold text-carimbo">{{ formatarSaldo(r.saldoLocalSegundos - r.saldoRhSegundos) }}</span>
              </td>
              <td class="px-2 py-2 text-right">{{ r.pendentes || '—' }}</td>
              <td v-if="auth.podeEscrever" class="pr-2 text-right">
                <button type="button" class="rounded-[3px] p-1.5 text-tinta-apagada hover:bg-papel-escuro hover:text-carimbo" :aria-label="`Remover ${r.nomeArquivo}`" title="Remover relatório" @click="excluirRelatorio(r)">
                  <svg viewBox="0 0 16 16" class="size-3.5" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 4h10M6 4V2.5h4V4M4.5 4l.7 9.5h5.6l.7-9.5" stroke-linejoin="round" /></svg>
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- Divergências -->
    <section class="mt-6" aria-label="Divergências">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex gap-1 rounded-[3px] border border-linha bg-cartao p-1 text-sm" role="tablist">
          <button
            v-for="s in [{ v: 'PENDENTE', r: `Pendentes (${resumo?.pendentes ?? 0})` }, { v: 'RESOLVIDAS', r: 'Decididas' }, { v: 'TODAS', r: 'Todas' }]"
            :key="s.v"
            type="button"
            role="tab"
            class="rounded-[2px] px-3 py-1 font-semibold"
            :class="(status === s.v || (s.v === 'RESOLVIDAS' && ['ACEITO_RH', 'MANTIDO_LOCAL', 'RESOLVIDA'].includes(status))) ? 'bg-tinta text-cartao' : 'text-tinta-suave hover:text-tinta'"
            :aria-selected="status === s.v"
            @click="status = s.v === 'RESOLVIDAS' ? 'ACEITO_RH' : s.v"
          >{{ s.r }}</button>
          <select
            v-if="['ACEITO_RH', 'MANTIDO_LOCAL', 'RESOLVIDA'].includes(status)"
            v-model="status"
            class="ml-1 rounded-[2px] border border-linha bg-cartao px-1 text-xs"
            aria-label="Tipo de decisão"
          >
            <option value="ACEITO_RH">Dados do RH aceitos</option>
            <option value="MANTIDO_LOCAL">Mantidos locais</option>
            <option value="RESOLVIDA">Resolvidas (ajuste/novo PDF)</option>
          </select>
        </div>
        <p class="text-sm text-tinta-suave" aria-live="polite">{{ filtradas.length }} dia(s)</p>
      </div>

      <!-- Filtro por tipo -->
      <div v-if="tiposComPendencia.length && status === 'PENDENTE'" class="mt-3 flex flex-wrap gap-1.5">
        <button
          type="button"
          class="rounded-full border px-2.5 py-0.5 text-xs font-semibold"
          :class="!filtroTipo ? 'border-tinta bg-tinta text-cartao' : 'border-linha text-tinta-suave hover:border-tinta'"
          @click="filtroTipo = null"
        >Todos os tipos</button>
        <button
          v-for="t in tiposComPendencia"
          :key="t.tipo"
          type="button"
          class="rounded-full border px-2.5 py-0.5 text-xs font-semibold"
          :class="filtroTipo === t.tipo ? 'border-tinta bg-tinta text-cartao' : 'border-linha text-tinta-suave hover:border-tinta'"
          :title="DICAS_TIPO[t.tipo]"
          @click="filtroTipo = filtroTipo === t.tipo ? null : t.tipo"
        >{{ t.rotulo }} · {{ t.pendentes }}</button>
      </div>

      <!-- Aceite em lote -->
      <div v-if="auth.podeEscrever && status === 'PENDENTE' && tiposComPendencia.length" class="cartao mt-3 px-4 py-3">
        <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
          <p class="rotulo">Aceitar em lote</p>
          <label v-for="t in tiposComPendencia" :key="t.tipo" class="flex items-center gap-1.5 text-sm" :title="DICAS_TIPO[t.tipo]">
            <input type="checkbox" class="accent-[var(--color-tinta)]" :checked="loteTipos.includes(t.tipo)" @change="alternarLote(t.tipo)" />
            {{ t.rotulo }} <span class="text-tinta-apagada">({{ t.pendentes }})</span>
          </label>
          <button type="button" class="botao-primario ml-auto py-1.5! text-xs" :disabled="aceitandoLote || !qtdLote" @click="aceitarLote">
            {{ aceitandoLote ? 'Aplicando…' : `Aceitar dados do RH em ${qtdLote} dia(s)` }}
          </button>
        </div>
        <p class="mt-1.5 text-xs text-tinta-suave">
          Cada dia vira igual ao RH, com histórico (“Conforme relatório do RH”). Batidas com PDF só têm os segundos alinhados, nunca são apagadas.
          Dias que não podem ser copiados (ex.: número ímpar de batidas) ficam para decisão individual.
        </p>
        <ul v-if="falhasLote.length" class="mt-2 space-y-0.5 text-xs text-carimbo">
          <li v-for="f in falhasLote" :key="f.data">{{ dataBR(f.data) }}: {{ f.mensagem }}</li>
        </ul>
      </div>

      <p v-if="carregando && !divergencias.length" class="mt-6 text-center text-sm text-tinta-suave">Carregando…</p>
      <p v-else-if="!filtradas.length" class="cartao mt-4 px-5 py-8 text-center text-sm text-tinta-suave">
        {{ resumo?.relatorios.length
          ? status === 'PENDENTE' ? 'Nenhuma divergência pendente: a conferência bate com o RH. ✓' : 'Nada por aqui.'
          : 'Envie um relatório do RH para começar.' }}
      </p>

      <ul class="mt-4 space-y-3">
        <li v-for="d in visiveis" :key="d.id" class="cartao overflow-hidden">
          <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-linha px-4 py-2.5">
            <span class="font-sans text-lg font-bold">{{ diaSemanaCurto(d.data) }} {{ dataBR(d.data) }}</span>
            <span class="rounded-[2px] bg-tinta px-1.5 py-0.5 text-[0.65rem] font-bold tracking-wide text-cartao uppercase" :title="DICAS_TIPO[d.tipo]">{{ d.tipoRotulo }}</span>
            <span
              v-if="d.status !== 'PENDENTE'"
              class="rounded-[2px] px-1.5 py-0.5 text-[0.65rem] font-bold tracking-wide uppercase"
              :class="d.status === 'MANTIDO_LOCAL' ? 'bg-amber-500/15 text-amber-800' : 'bg-credito/10 text-credito'"
            >{{ ROTULO_STATUS[d.status] }}</span>
            <span class="text-sm text-tinta-suave">{{ d.descricao }}</span>
          </div>

          <div class="grid gap-px bg-linha sm:grid-cols-2">
            <!-- Conferência -->
            <div class="bg-cartao px-4 py-3">
              <p class="rotulo">Conferência · {{ ROTULO_TIPO_DIA[d.tipoDiaLocal] ?? '—' }}</p>
              <template v-if="d.local">
                <p class="mt-1.5 flex flex-wrap gap-x-2 gap-y-1">
                  <span v-for="(b, i) in lados(d).local" :key="i" class="carimbo rounded-[2px] px-1" :class="classeHora[b.estado]">{{ b.h }}</span>
                </p>
                <p class="mt-1.5 text-sm text-tinta-suave">
                  trabalhado <b class="carimbo text-tinta">{{ formatarDuracao(d.local.segundosTrabalhados) }}</b> · saldo
                  <b class="carimbo" :class="classeSaldo(d.local.saldoDiarioSegundos)">{{ d.local.status === 'FECHADA' ? formatarSaldo(d.local.saldoDiarioSegundos) : 'incompleto' }}</b>
                </p>
              </template>
              <p v-else class="mt-1.5 text-sm italic text-tinta-apagada">sem registro neste dia</p>
            </div>
            <!-- RH -->
            <div class="bg-papel/60 px-4 py-3">
              <p class="rotulo">RH<template v-if="d.rh?.relatorioEmitidoEm"> · relatório de {{ dataBR(d.rh.relatorioEmitidoEm) }}</template></p>
              <template v-if="d.rh">
                <p v-if="d.rh.ocorrencia" class="mt-1.5 font-semibold">{{ d.rh.ocorrencia }}</p>
                <p v-if="d.rh.horarios.length" class="mt-1.5 flex flex-wrap gap-x-2 gap-y-1">
                  <span v-for="(b, i) in lados(d).rh" :key="i" class="carimbo rounded-[2px] px-1" :class="classeHora[b.estado]">{{ b.h }}</span>
                </p>
                <p v-else-if="!d.rh.ocorrencia" class="mt-1.5 text-sm italic text-tinta-apagada">sem batidas</p>
                <p class="mt-1.5 text-sm text-tinta-suave">
                  trabalhado <b class="carimbo text-tinta">{{ formatarDuracao(d.rh.segundosTrabalhados) }}</b> · saldo
                  <b class="carimbo" :class="classeSaldo(d.rh.saldoSegundos)">{{ formatarSaldo(d.rh.saldoSegundos) }}</b>
                </p>
              </template>
              <p v-else class="mt-1.5 text-sm italic text-tinta-apagada">relatório removido</p>
            </div>
          </div>

          <!-- Ações -->
          <div v-if="auth.podeEscrever" class="flex flex-wrap items-center gap-2 border-t border-linha px-4 py-2.5">
            <template v-if="d.status === 'PENDENTE'">
              <button
                type="button"
                class="botao-primario py-1.5! text-xs"
                :disabled="!d.aceitavel || ocupado.has(d.id)"
                :title="d.aceitavel ? 'A conferência fica igual ao RH (com histórico)' : d.motivoNaoAceitavel"
                @click="aceitar(d)"
              >Aceitar dados do RH</button>
              <button type="button" class="botao-secundario py-1.5! text-xs" :disabled="ocupado.has(d.id)" @click="iniciarManter(d)">Manter dados locais</button>
              <RouterLink v-if="d.tipo === 'TIPO_DIA'" :to="{ name: 'ausencias' }" class="botao-secundario py-1.5! text-xs">Férias e folgas</RouterLink>
              <button
                v-else
                type="button"
                class="botao-secundario py-1.5! text-xs"
                :disabled="ocupado.has(d.id) || !d.rh"
                @click="ajustarManual(d)"
              >Ajuste manual</button>
              <span v-if="!d.aceitavel && d.motivoNaoAceitavel" class="text-xs text-tinta-suave">{{ d.motivoNaoAceitavel }}</span>
            </template>
            <template v-else>
              <span class="text-xs text-tinta-suave">
                {{ ROTULO_STATUS[d.status] }} por {{ d.resolvidaPor }} em {{ formatarMomento(d.resolvidaEm) }}<template v-if="d.observacao"> · “{{ d.observacao }}”</template>
              </span>
              <button type="button" class="ml-auto text-xs font-semibold text-tinta-suave underline underline-offset-4 hover:text-tinta" :disabled="ocupado.has(d.id)" @click="reabrir(d)">Reabrir</button>
            </template>
          </div>
          <form v-if="mantendo?.id === d.id" class="flex flex-wrap items-center gap-2 border-t border-dashed border-linha bg-papel/50 px-4 py-2.5" @submit.prevent="confirmarManter(d)">
            <label class="sr-only" :for="`obs-${d.id}`">Observação</label>
            <input :id="`obs-${d.id}`" v-model="mantendo.observacao" type="text" maxlength="300" class="campo flex-1 py-1.5! font-sans text-sm" placeholder="Motivo (opcional): ex.: RH vai corrigir" />
            <button type="submit" class="botao-primario py-1.5! text-xs">Confirmar</button>
            <button type="button" class="botao-secundario py-1.5! text-xs" @click="mantendo = null">Cancelar</button>
          </form>
        </li>
      </ul>
      <div v-if="filtradas.length > visiveis.length" class="mt-4 text-center">
        <button type="button" class="botao-secundario" @click="limite += 40">Mostrar mais ({{ filtradas.length - visiveis.length }})</button>
      </div>

      <p class="mt-4 text-xs text-tinta-suave">
        <span class="carimbo rounded-[2px] bg-carimbo/10 px-1 font-semibold text-carimbo">12:00:25</span> = batida só de um lado ·
        <span class="carimbo text-amber-800 underline decoration-dotted underline-offset-4">08:05:53</span> = mesma batida com segundos diferentes
        (o comprovante em PDF costuma marcar 1 s depois do RH).
      </p>
    </section>

    <ModalAjusteBatidas
      v-if="auth.podeEscrever"
      v-model="ajuste.aberto"
      :data="ajuste.data"
      :registro="ajuste.registro"
      :sugestao-rh="ajuste.sugestao"
      :motivo-sugerido="ajuste.motivo"
      @salvo="aoSalvarAjuste"
    />

    <Transition enter-active-class="transition duration-200" enter-from-class="translate-y-3 opacity-0" leave-active-class="transition duration-150" leave-to-class="opacity-0">
      <div
        v-if="aviso"
        role="status"
        class="fixed inset-x-4 bottom-4 z-40 mx-auto max-w-md rounded-[3px] px-4 py-3 text-sm font-medium shadow-lg sm:inset-x-auto sm:right-6 sm:bottom-6"
        :class="{ 'bg-carimbo text-cartao': aviso.tipo === 'erro', 'bg-tinta text-cartao': aviso.tipo === 'ok', 'border border-tinta bg-cartao text-tinta': aviso.tipo === 'info' }"
      >{{ aviso.texto }}</div>
    </Transition>
  </div>
</template>
