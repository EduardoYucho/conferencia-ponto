<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { usePontoStore } from '@/stores/ponto'
import { useAuthStore } from '@/stores/auth'
import { useRelogio } from '@/composables/useRelogio'
import BarraProgressoDiaria from '@/components/BarraProgressoDiaria.vue'
import CartaoMensal from '@/components/CartaoMensal.vue'
import EnvioComprovantes from '@/components/EnvioComprovantes.vue'
import GraficoSaldoAnual from '@/components/GraficoSaldoAnual.vue'
import ModalAjusteBatidas from '@/components/ModalAjusteBatidas.vue'
import ModalCicloBanco from '@/components/ModalCicloBanco.vue'
import ModalDiaEspecial from '@/components/ModalDiaEspecial.vue'
import ModalLancamentoBanco from '@/components/ModalLancamentoBanco.vue'
import ModalLancamentoManual from '@/components/ModalLancamentoManual.vue'
import TimelineDiaria from '@/components/TimelineDiaria.vue'
import {
  dataBR, dataCurta, diaSemanaCurto, formatarDuracao, formatarSaldo, nomeMes, paraSegundos, segundosAgora,
} from '@/utils/tempo'

const store = usePontoStore()
/** ROLE_VIEWER: mesma tela, sem botões de edição, inserção ou exclusão. */
const auth = useAuthStore()
const {
  configuracao, ano, mes, resumo, carregando, salvando, erro,
  hoje, diasPorData, registroHoje, diaSelecionado, dataSelecionada,
  saldoMensal, ciclo, marcadoresDoMes, lancamentosPorData, lancamentosMes, ehMesAtual, expedientePorData,
  tempoReal, monitoramento, ultimoEvento, cargaDiaInteiro,
} = storeToRefs(store)

const agora = useRelogio(1000)
const modalAberto = ref(false)
const dataModal = ref(null)
const ajusteAberto = ref(false)
const dataAjuste = ref(null)
const aviso = ref(null)
const confirmandoExclusao = ref(false)
const destaque = ref(null) // { data, chave } — realça a linha alterada por um evento
const cicloAberto = ref(false)
const modoCiclo = ref('fechar')
const bancoAberto = ref(false)
const preBanco = ref({ data: null, duracao: '', descricao: '' })
const diaEspecialAberto = ref(false)
const dataEspecial = ref(null)
const confirmandoLancamento = ref(null)
/** Envio dos comprovantes pela tela (botão ou arrastar PDFs para a página). */
const envioAberto = ref(false)
const envio = ref(null)
const arrastandoArquivo = ref(false)
let timerAviso = null
let timerConfirmacao = null

onMounted(() => store.fetchMesAtual().catch(() => {}))
onBeforeUnmount(() => {
  clearTimeout(timerAviso)
  clearTimeout(timerConfirmacao)
})

// ---------------------------------------------------------- tempo real
/** Eventos vindos do SSE: avisa sobre PDFs e realça o dia alterado. */
watch(ultimoEvento, (evento) => {
  if (!evento) return
  if (evento.tipo === 'jornada-atualizada') {
    destaque.value = { data: evento.data, chave: evento.recebidoEm }
    if (evento.origem === 'COMPROVANTE_PDF') mostrarAviso(`PDF importado · ${evento.mensagem}`, 'pdf')
  } else if (evento.tipo === 'comprovante-nao-importado') {
    mostrarAviso(`${evento.nomeArquivo}: ${evento.mensagem}`, evento.status === 'DUPLICADO' ? 'info' : 'erro')
  }
})

/** Carga prevista hoje pelo horário da pessoa (0 = sem expediente). */
const cargaHoje = computed(() => (registroHoje.value ? registroHoje.value.jornadaPrevistaSegundos : store.cargaDoDia(hoje.value)))
const rotuloJornada = computed(() => {
  const quem = auth.vendoOsProprios ? '' : ` de ${auth.pessoaEmTela?.nome ?? ''}`
  return `Banco de horas${quem} · jornada ${formatarDuracao(cargaDiaInteiro.value, { curto: true })}`
})

// ------------------------------------------------- envio dos comprovantes pela tela
function abrirEnvio() {
  envioAberto.value = true
}

function aoEnviarComprovantes({ total, importados }) {
  mostrarAviso(importados
    ? `${importados} de ${total} comprovante(s) importado(s)`
    : `Nenhuma batida nova nos ${total} comprovante(s) enviados`, importados ? 'pdf' : 'info')
}

/** Arrastar PDFs para qualquer lugar do painel abre o envio já com os arquivos. */
function temArquivos(evento) {
  return auth.podeEscrever && [...(evento.dataTransfer?.types ?? [])].includes('Files')
}
function aoArrastarSobre(evento) {
  if (!temArquivos(evento)) return
  evento.preventDefault()
  arrastandoArquivo.value = true
}
function aoSairArrastando(evento) {
  if (!evento.relatedTarget) arrastandoArquivo.value = false
}
async function aoSoltarNaPagina(evento) {
  arrastandoArquivo.value = false
  if (!temArquivos(evento)) return
  evento.preventDefault()
  const arquivos = [...evento.dataTransfer.files]
  envioAberto.value = true
  await new Promise((r) => setTimeout(r, 0))
  envio.value?.enviar(arquivos)
}
function fecharEnvio() {
  envioAberto.value = false
}
function aoTeclarEnvio(evento) {
  if (evento.key === 'Escape' && envioAberto.value) fecharEnvio()
}
onMounted(() => window.addEventListener('keydown', aoTeclarEnvio))
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclarEnvio))

const pastaMonitorada = computed(() => {
  const diretorio = monitoramento.value?.diretorio ?? ''
  if (/^(\\\\|\/\/)/.test(diretorio)) return diretorio.replaceAll('/', '\\').replace(/\\$/, '') // pasta de rede: caminho inteiro
  const partes = diretorio.split(/[\\/]/).filter(Boolean)
  return `…/${partes.slice(-2).join('/')}`
})

const indicadorTempoReal = computed(() => {
  if (tempoReal.value !== 'conectado') {
    return { cor: 'bg-amber-500 animate-pulse', texto: tempoReal.value === 'desconectado' ? 'Offline' : 'Reconectando…' }
  }
  const monitor = monitoramento.value
  if (monitor?.ativo) return { cor: 'bg-credito', texto: `Importando PDFs de ${pastaMonitorada.value}` }
  if (monitor?.situacao === 'INDISPONIVEL') {
    return { cor: 'bg-amber-500 animate-pulse', texto: 'Pasta dos PDFs inacessível · tentando reconectar' }
  }
  if (monitor?.situacao === 'INICIANDO') return { cor: 'bg-amber-500 animate-pulse', texto: 'Conectando à pasta dos PDFs…' }
  if (monitor?.situacao === 'SEM_PASTA') return { cor: 'bg-credito', texto: 'Tempo real · sem pasta de PDFs (envio pela tela)' }
  return { cor: 'bg-tinta-apagada', texto: 'Tempo real · monitor de PDF inativo' }
})

const dicaMonitor = computed(() => {
  const monitor = monitoramento.value
  if (!monitor?.diretorio) return 'Conexão em tempo real (SSE)'
  const base = `Pasta monitorada: ${monitor.diretorio}`
  return monitor.mensagem ? `${base}\nMotivo: ${monitor.mensagem}` : base
})

// ------------------------------------------------------------------ hoje
const proximaBatida = computed(() => registroHoje.value?.batidas.find((b) => !b.real) ?? { rotulo: 'Entrada 1' })
/** Com 4 batidas o dia está fechado (a 3ª entrada/saída é exceção: vem dos PDFs ou do ajuste). */
const jornadaCompleta = computed(() => {
  const reais = registroHoje.value?.batidas.filter((b) => b.real).length ?? 0
  return reais >= 4 && reais % 2 === 0
})

/** Segundos de hoje incluindo o intervalo aberto (batidas reais, como o "Hr. Trabalhadas" do RH). */
const segundosHoje = computed(() => {
  const r = registroHoje.value
  if (!r) return 0
  let total = r.segundosTrabalhados
  if (r.status === 'EM_ANDAMENTO') {
    const ultima = [...r.batidas].reverse().find((b) => b.real)
    if (ultima?.tipo.startsWith('ENTRADA')) {
      total += Math.max(0, segundosAgora(agora.value) - paraSegundos(ultima.real))
    }
  }
  return total
})

const saldoHoje = computed(() => {
  const r = registroHoje.value
  if (!r) return null
  return r.status === 'FECHADA' ? r.saldoDiarioSegundos : segundosHoje.value - r.jornadaPrevistaSegundos
})

// ------------------------------------------------------ banco de horas (ciclo)
const prazoCiclo = computed(() => {
  const c = ciclo.value
  if (!c || c.diasAtePrevisao === null) return null
  const d = c.diasAtePrevisao
  if (d < 0) return { texto: `previsão passou há ${-d} dia(s)`, urgente: true }
  if (d === 0) return { texto: 'previsto para hoje', urgente: true }
  return { texto: `em ${d} dia(s)`, urgente: d <= 30 }
})

function abrirCiclo(modo) {
  modoCiclo.value = modo
  cicloAberto.value = true
}

function aoConcluirCiclo(resultado) {
  if (resultado) {
    mostrarAviso(`Banco fechado com ${formatarSaldo(resultado.fechado.saldoSegundos)} · contagem recomeçou em ${dataBR(resultado.novo.dataInicio)}`)
  }
}

const relogio = computed(() => agora.value.toLocaleTimeString('pt-BR', { hour12: false }))

// ------------------------------------------------------------- ações
function mostrarAviso(texto, tipo = 'ok') {
  aviso.value = { texto, tipo }
  clearTimeout(timerAviso)
  timerAviso = setTimeout(() => (aviso.value = null), 5000)
}

async function baterPonto() {
  try {
    const registro = await store.postBatida()
    const b = [...registro.batidas].reverse().find((x) => x.real)
    const detalhe = b.considerado && b.considerado !== b.real
      ? ` → considerado ${b.considerado}${b.toleranciaAplicada ? ' (tolerância)' : ''}`
      : ''
    mostrarAviso(`${b.rotulo} registrada às ${b.real}${detalhe}`)
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  }
}

function abrirLancamento(data = null) {
  dataModal.value = data
  modalAberto.value = true
}

function abrirAjuste(data) {
  dataAjuste.value = data
  ajusteAberto.value = true
}

/** Lançamento avulso no banco (abater/creditar). */
function abrirBanco(data = null, { duracao = '', descricao = '' } = {}) {
  preBanco.value = { data, duracao, descricao }
  bancoAberto.value = true
}

function aoLancarNoBanco(l) {
  mostrarAviso(`${l.segundos < 0 ? 'Abatido' : 'Creditado'} ${formatarDuracao(Math.abs(l.segundos))} no banco em ${dataCurta(l.data)} · ${l.descricao}`)
}

async function removerLancamento(l) {
  if (confirmandoLancamento.value !== l.id) {
    confirmandoLancamento.value = l.id
    clearTimeout(timerConfirmacao)
    timerConfirmacao = setTimeout(() => (confirmandoLancamento.value = null), 4000)
    return
  }
  confirmandoLancamento.value = null
  try {
    await store.excluirLancamentoBanco(l)
    mostrarAviso(`Lançamento de ${dataCurta(l.data)} (${formatarSaldo(l.segundos)}) removido do banco`)
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  }
}

/** Folga, feriado ou justificativa no dia (ou ver/remover a marcação existente). */
function abrirDiaEspecial(data) {
  dataEspecial.value = data
  diaEspecialAberto.value = true
}

/** "Folga compensando o banco": lança um dia inteiro do horário da pessoa como débito. */
function compensarDia(data) {
  const carga = store.cargaDoDia(data) || cargaDiaInteiro.value
  abrirBanco(data, { duracao: formatarDuracao(carga, { curto: true }), descricao: 'Folga compensada' })
}

function aoSalvarAjuste(registro) {
  const saldo = registro.status === 'FECHADA'
    ? `saldo do dia ${formatarSaldo(registro.saldoDiarioSegundos)}`
    : 'o dia continua incompleto'
  mostrarAviso(`Batidas de ${dataCurta(registro.data)} ajustadas · ${saldo}`)
}

/** Dia que já passou com entrada sem saída: faltou batida. */
const selecionadoIncompleto = computed(() =>
  diaSelecionado.value?.status === 'EM_ANDAMENTO' && diaSelecionado.value.data < hoje.value)

function aoSalvarManual(registro) {
  mostrarAviso(`Lançamento de ${dataCurta(registro.data)} salvo: ${formatarSaldo(registro.saldoDiarioSegundos)} de crédito`)
}

async function excluirSelecionado() {
  if (!confirmandoExclusao.value) {
    confirmandoExclusao.value = true
    clearTimeout(timerConfirmacao)
    timerConfirmacao = setTimeout(() => (confirmandoExclusao.value = false), 4000)
    return
  }
  confirmandoExclusao.value = false
  const data = dataSelecionada.value
  try {
    await store.excluirRegistro(data)
    mostrarAviso(`Registro de ${dataCurta(data)} excluído`)
  } catch (e) {
    mostrarAviso(e.message, 'erro')
  }
}

function classeSaldo(seg) {
  if (!seg) return 'text-tinta'
  return seg > 0 ? 'text-credito' : 'text-carimbo'
}

const rotuloTipoDia = { UTIL: 'Dia útil', FIM_DE_SEMANA: 'Fim de semana', FERIADO: 'Feriado', AUSENCIA: 'Ausência · jornada base zero' }
</script>

<template>
  <div
    class="mx-auto max-w-6xl px-4 pb-16 sm:px-6"
    @dragover="aoArrastarSobre"
    @dragleave="aoSairArrastando"
    @drop="aoSoltarNaPagina"
  >
    <!-- Cabeçalho -->
    <header class="flex flex-wrap items-end justify-between gap-x-6 gap-y-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">{{ rotuloJornada }}</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">
          Conferência de Ponto
        </h1>
      </div>

      <div class="flex flex-wrap items-center gap-4">
        <p
          class="flex items-center gap-2 rounded-full border border-linha bg-cartao/70 px-3 py-1 text-xs text-tinta-suave"
          :title="dicaMonitor"
          role="status"
        >
          <span class="size-2 rounded-full" :class="indicadorTempoReal.cor" aria-hidden="true" />
          {{ indicadorTempoReal.texto }}
        </p>
        <time class="carimbo hidden text-2xl font-medium text-tinta-suave sm:block" :datetime="hoje">{{ relogio }}</time>
        <template v-if="auth.podeEscrever">
        <button
          type="button"
          class="botao-primario"
          :disabled="salvando || jornadaCompleta || !configuracao"
          @click="baterPonto"
        >
          <svg viewBox="0 0 20 20" class="size-4" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <circle cx="10" cy="10" r="7.5" /><path d="M10 6v4l2.5 2" stroke-linecap="round" />
          </svg>
          {{ jornadaCompleta ? 'Jornada completa' : `Bater ${proximaBatida.rotulo}` }}
        </button>
        <button type="button" class="botao-secundario" @click="abrirLancamento()">Lançamento manual</button>
        <button type="button" class="botao-secundario" title="Abater ou creditar horas no banco (compensação, horas pagas...)" @click="abrirBanco()">Lançar no banco</button>
        <button type="button" class="botao-secundario" title="Enviar comprovantes em PDF (ou arraste os arquivos para a página)" @click="abrirEnvio">Enviar PDFs</button>
        </template>
      </div>
    </header>

    <!-- Navegação de mês -->
    <nav class="flex items-center justify-between py-4" aria-label="Navegação entre meses">
      <button type="button" class="botao-secundario px-3!" :disabled="carregando || !ano" aria-label="Mês anterior" @click="store.navegarMes(-1)">‹</button>
      <p class="text-center">
        <span class="font-sans text-xl font-bold uppercase tracking-[0.2em] [font-stretch:90%]">{{ mes ? nomeMes(mes) : '—' }}</span>
        <span class="carimbo ml-2 text-xl text-tinta-suave">{{ ano }}</span>
        <button
          v-if="ano && !ehMesAtual"
          type="button"
          class="ml-3 text-xs font-semibold text-carimbo underline underline-offset-4"
          @click="store.fetchMesAtual()"
        >voltar para hoje</button>
      </p>
      <button type="button" class="botao-secundario px-3!" :disabled="carregando || !ano" aria-label="Próximo mês" @click="store.navegarMes(1)">›</button>
    </nav>

    <p v-if="erro && !configuracao" role="alert" class="cartao border-carimbo/50 px-5 py-4 text-carimbo">
      {{ erro.message }}
    </p>

    <!-- Aviso de prazo do banco de horas (30/15 dias ou previsão vencida) -->
    <p
      v-if="ciclo && prazoCiclo?.urgente"
      role="status"
      class="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-4 py-2.5 text-sm"
    >
      <span>
        <b class="text-carimbo">Banco de horas:</b>
        {{ ciclo.diasAtePrevisao < 0
          ? `a previsão de fechamento (${dataBR(ciclo.dataFimPrevista)}) passou e o ciclo continua aberto.`
          : `faltam ${ciclo.diasAtePrevisao} dia(s) para o fechamento previsto (${dataBR(ciclo.dataFimPrevista)}).` }}
        Saldo do ciclo <b class="carimbo">{{ formatarSaldo(ciclo.saldoSegundos) }}</b>.
      </span>
      <button v-if="auth.podeEscrever" type="button" class="botao-secundario py-1! text-xs" @click="abrirCiclo('fechar')">Fechar banco de horas</button>
    </p>

    <!-- Painéis de saldo -->
    <section class="grid gap-4 md:grid-cols-[1.35fr_1fr_1fr]" aria-label="Saldos">
      <article class="cartao animate-surgir px-5 py-5">
        <div class="mb-4 flex items-center justify-between">
          <h2 class="rotulo">Hoje · {{ diaSemanaCurto(hoje) }} {{ dataCurta(hoje) }}</h2>
          <span
            v-if="registroHoje?.status === 'EM_ANDAMENTO'"
            class="flex items-center gap-1.5 text-[0.68rem] font-bold uppercase tracking-wider text-carimbo"
          >
            <span class="size-1.5 animate-pulse rounded-full bg-carimbo" /> em andamento
          </span>
        </div>
        <BarraProgressoDiaria
          :segundos="segundosHoje"
          :jornada-base="cargaHoje"
          :em-andamento="registroHoje?.status === 'EM_ANDAMENTO'"
          :sem-jornada="cargaHoje === 0"
        />
        <p class="mt-1 text-sm text-tinta-suave">
          Saldo do dia
          <span class="carimbo ml-1 font-semibold" :class="classeSaldo(saldoHoje)">{{ formatarSaldo(saldoHoje) }}</span>
          <span v-if="registroHoje?.status === 'EM_ANDAMENTO'" class="text-xs italic"> (parcial)</span>
        </p>
      </article>

      <article class="cartao animate-surgir px-5 py-5 [animation-delay:80ms]">
        <h2 class="rotulo">Saldo de {{ mes ? nomeMes(mes).toLowerCase() : '—' }}</h2>
        <p class="carimbo mt-3 text-4xl font-semibold sm:text-5xl" :class="classeSaldo(saldoMensal)">
          {{ formatarSaldo(saldoMensal) }}
        </p>
        <dl v-if="resumo" class="mt-4 grid grid-cols-2 gap-y-1 text-sm">
          <dt class="text-tinta-suave">Trabalhado</dt>
          <dd class="carimbo text-right">{{ formatarDuracao(resumo.segundosTrabalhados) }}</dd>
          <dt class="text-tinta-suave">Previsto</dt>
          <dd class="carimbo text-right">{{ formatarDuracao(resumo.segundosPrevistos) }}</dd>
          <dt class="text-tinta-suave">Dias fechados</dt>
          <dd class="carimbo text-right">{{ resumo.diasRegistrados - resumo.diasEmAberto }}</dd>
          <template v-if="resumo.segundosLancados">
            <dt class="text-tinta-suave">Lançado no banco</dt>
            <dd class="carimbo text-right font-semibold" :class="classeSaldo(resumo.segundosLancados)">{{ formatarSaldo(resumo.segundosLancados) }}</dd>
          </template>
        </dl>
        <p v-if="resumo?.diasEmAberto" class="mt-2 text-xs italic text-tinta-suave">
          {{ resumo.diasEmAberto }} dia(s) em andamento ou incompleto(s) fora do saldo
        </p>
      </article>

      <article class="cartao animate-surgir px-5 py-5 [animation-delay:160ms]" aria-label="Banco de horas do ciclo atual">
        <div class="flex items-start justify-between gap-2">
          <h2 class="rotulo">Banco de horas · ciclo atual</h2>
          <button
            v-if="ciclo && auth.podeEscrever"
            type="button"
            class="-mt-1 rounded-[3px] px-1.5 py-0.5 text-[0.7rem] font-semibold text-tinta-suave underline decoration-linha underline-offset-4 hover:text-tinta"
            @click="abrirCiclo('periodo')"
          >período</button>
        </div>
        <template v-if="ciclo">
          <p class="carimbo mt-3 text-4xl font-semibold sm:text-5xl" :class="classeSaldo(ciclo.saldoSegundos)">
            {{ formatarSaldo(ciclo.saldoSegundos) }}
          </p>
          <p class="mt-1 text-xs text-tinta-suave">
            desde {{ dataBR(ciclo.dataInicio) }} · fecha em {{ dataBR(ciclo.dataFimPrevista) }}
            <span :class="prazoCiclo?.urgente ? 'font-semibold text-carimbo' : ''">({{ prazoCiclo?.texto }})</span>
          </p>
          <GraficoSaldoAnual class="mt-3" :meses="ciclo.meses" :destaque="{ ano, mes }" rotulo-acumulado="no ciclo" />
          <button
            v-if="auth.podeEscrever"
            type="button"
            class="mt-3 w-full py-1.5! text-xs"
            :class="prazoCiclo?.urgente ? 'botao-primario' : 'botao-secundario'"
            @click="abrirCiclo('fechar')"
          >Fechar banco de horas</button>
        </template>
        <p v-else class="mt-3 text-sm text-tinta-suave">{{ carregando ? 'Carregando…' : 'Ciclo não encontrado.' }}</p>
      </article>
    </section>

    <!-- Linha do tempo do dia selecionado -->
    <section class="cartao mt-6 animate-surgir px-5 py-5 [animation-delay:240ms]" aria-label="Linha do tempo do dia">
      <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 class="rotulo">Linha do tempo · oficial x real</h2>
          <p v-if="diaSelecionado" class="mt-1 flex flex-wrap items-center gap-2">
            <span class="font-sans text-lg font-bold">{{ diaSemanaCurto(diaSelecionado.data) }} {{ dataCurta(diaSelecionado.data) }}</span>
            <span class="rounded-[2px] border border-linha px-1.5 text-xs text-tinta-suave">{{ rotuloTipoDia[diaSelecionado.tipoDia] }}</span>
            <span v-if="diaSelecionado.registroManual" class="rounded-[2px] bg-tinta/10 px-1.5 text-xs text-tinta-suave">lançamento manual</span>
            <span v-if="diaSelecionado.ajustado" class="rounded-[2px] bg-tinta/10 px-1.5 text-xs text-tinta-suave">ajustado manualmente</span>
            <span class="text-sm text-tinta-suave">
              · trabalhado <b class="carimbo text-tinta">{{ formatarDuracao(diaSelecionado.segundosTrabalhados) }}</b>
              · saldo
              <b class="carimbo" :class="classeSaldo(diaSelecionado.saldoDiarioSegundos)">
                {{ selecionadoIncompleto ? 'incompleto' : diaSelecionado.status === 'EM_ANDAMENTO' ? 'em andamento' : formatarSaldo(diaSelecionado.saldoDiarioSegundos) }}
              </b>
            </span>
          </p>
        </div>
        <div v-if="diaSelecionado && auth.podeEscrever" class="flex gap-2">
          <button
            v-if="diaSelecionado.registroManual"
            type="button"
            class="botao-secundario py-1.5! text-xs"
            @click="abrirLancamento(diaSelecionado.data)"
          >Editar lançamento</button>
          <button
            v-else
            type="button"
            class="py-1.5! text-xs"
            :class="selecionadoIncompleto ? 'botao-primario' : 'botao-secundario'"
            @click="abrirAjuste(diaSelecionado.data)"
          >{{ selecionadoIncompleto ? 'Faltou batida · ajustar' : 'Ajustar batidas' }}</button>
          <button
            type="button"
            class="botao py-1.5! text-xs"
            :class="confirmandoExclusao ? 'bg-carimbo text-cartao' : 'border border-linha text-tinta-suave hover:border-carimbo hover:text-carimbo'"
            :disabled="salvando"
            @click="excluirSelecionado"
          >{{ confirmandoExclusao ? 'Confirmar exclusão?' : 'Excluir registro' }}</button>
        </div>
      </div>

      <TimelineDiaria
        v-if="diaSelecionado"
        :registro="diaSelecionado"
        :periodos="store.periodosDoDia(diaSelecionado.data)"
        :tolerancia-minutos="configuracao?.toleranciaMinutos ?? 5"
        :hoje="hoje"
        :agora="agora"
      />
      <p v-else class="py-8 text-center text-sm text-tinta-suave">
        {{ carregando ? 'Carregando…' : auth.podeEscrever
          ? 'Selecione um dia com registro no cartão abaixo, ou bata o ponto para começar.'
          : 'Selecione um dia com registro no cartão abaixo.' }}
      </p>
    </section>

    <!-- Cartão do mês -->
    <section class="cartao perfurado mt-6 animate-surgir py-4 pr-4 [animation-delay:320ms]" aria-label="Cartão de ponto do mês">
      <div class="mb-3 flex flex-wrap items-baseline justify-between gap-2 pl-2">
        <h2 class="rotulo">Cartão de ponto · {{ mes ? nomeMes(mes) : '' }} {{ ano }}</h2>
        <p class="text-xs text-tinta-suave">
          <span class="carimbo text-tinta">08:02:10</span> tolerado ·
          <span class="carimbo font-semibold text-carimbo">08:05:22</span> fora da tolerância de {{ configuracao?.toleranciaMinutos ?? 5 }}:00
        </p>
      </div>
      <CartaoMensal
        v-if="ano"
        :ano="ano"
        :mes="mes"
        :dias-por-data="diasPorData"
        :hoje="hoje"
        :selecionada="dataSelecionada"
        :destacada="destaque"
        :somente-leitura="!auth.podeEscrever"
        :marcadores="marcadoresDoMes"
        :lancamentos="lancamentosPorData"
        :expedientes="expedientePorData"
        @selecionar="store.selecionarDia"
        @lancar="abrirLancamento"
        @ajustar="abrirAjuste"
        @marcar="abrirDiaEspecial"
      />
    </section>

    <!-- Lançamentos avulsos no banco de horas do mês -->
    <section v-if="lancamentosMes.length" class="cartao mt-6 overflow-hidden" aria-label="Lançamentos no banco de horas do mês">
      <div class="flex flex-wrap items-center justify-between gap-2 border-b border-linha px-5 py-3">
        <h2 class="rotulo">Lançamentos no banco · {{ mes ? nomeMes(mes) : '' }} {{ ano }}</h2>
        <button v-if="auth.podeEscrever" type="button" class="text-xs font-semibold text-tinta-suave underline underline-offset-4 hover:text-tinta" @click="abrirBanco()">+ lançar</button>
      </div>
      <ul class="divide-y divide-linha/70">
        <li v-for="l in lancamentosMes" :key="l.id" class="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-2.5">
          <span class="carimbo w-28 font-semibold">{{ diaSemanaCurto(l.data) }} {{ dataCurta(l.data) }}</span>
          <span class="carimbo w-24 font-semibold" :class="classeSaldo(l.segundos)">{{ formatarSaldo(l.segundos) }}</span>
          <span class="min-w-0 flex-1 text-sm">{{ l.descricao }}</span>
          <span class="text-xs text-tinta-apagada">por {{ l.criadoPor }}</span>
          <button
            v-if="auth.podeEscrever"
            type="button"
            class="rounded-[3px] px-2 py-1 text-xs font-semibold transition"
            :class="confirmandoLancamento === l.id ? 'bg-carimbo text-cartao' : 'text-tinta-suave hover:bg-papel-escuro hover:text-carimbo'"
            :disabled="salvando"
            @click="removerLancamento(l)"
          >{{ confirmandoLancamento === l.id ? 'Confirmar remoção?' : 'Remover' }}</button>
        </li>
      </ul>
    </section>

    <ModalLancamentoManual v-if="auth.podeEscrever" v-model="modalAberto" :data-inicial="dataModal" @salvo="aoSalvarManual" />
    <ModalCicloBanco v-if="auth.podeEscrever" v-model="cicloAberto" :modo="modoCiclo" :ciclo="ciclo" @concluido="aoConcluirCiclo" />
    <ModalLancamentoBanco
      v-if="auth.podeEscrever"
      v-model="bancoAberto"
      :data-inicial="preBanco.data"
      :duracao-inicial="preBanco.duracao"
      :descricao-inicial="preBanco.descricao"
      @salvo="aoLancarNoBanco"
    />
    <ModalDiaEspecial
      v-if="auth.podeEscrever"
      v-model="diaEspecialAberto"
      :data="dataEspecial"
      :marcador="dataEspecial ? marcadoresDoMes[dataEspecial] ?? null : null"
      @salvo="(texto) => mostrarAviso(texto)"
      @compensar="compensarDia"
    />
    <!-- Envio dos comprovantes pela tela -->
    <Teleport to="body">
      <div
        v-if="arrastandoArquivo && !envioAberto"
        class="pointer-events-none fixed inset-3 z-50 grid place-items-center rounded-[6px] border-4 border-dashed border-tinta bg-papel/85"
      >
        <p class="font-sans text-2xl font-bold">Solte os comprovantes em PDF</p>
      </div>
      <div
        v-if="envioAberto"
        class="fixed inset-0 z-50 flex items-end justify-center bg-tinta/45 p-0 backdrop-blur-[2px] sm:items-center sm:p-6"
        @mousedown.self="fecharEnvio"
      >
        <section role="dialog" aria-modal="true" aria-labelledby="titulo-envio" class="cartao perfurado w-full max-w-lg animate-surgir rounded-b-none sm:rounded-[3px]">
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">Comprovantes de ponto</p>
              <h2 id="titulo-envio" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">Enviar PDFs</h2>
              <p class="mt-1 text-sm text-tinta-suave">
                Cada comprovante vira a batida do dia. Para importar sozinho, configure a pasta em
                <RouterLink :to="{ name: 'conta' }" class="font-semibold underline underline-offset-4">Minha conta</RouterLink>.
              </p>
            </div>
            <button type="button" class="-mr-1 rounded-[3px] p-1.5 text-tinta-suave hover:bg-papel-escuro hover:text-tinta" aria-label="Fechar" @click="fecharEnvio">
              <svg viewBox="0 0 20 20" class="size-5" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M5 5l10 10M15 5L5 15" /></svg>
            </button>
          </header>
          <div class="px-5 py-5">
            <EnvioComprovantes ref="envio" @enviados="aoEnviarComprovantes" />
          </div>
        </section>
      </div>
    </Teleport>

    <ModalAjusteBatidas
      v-if="auth.podeEscrever"
      v-model="ajusteAberto"
      :data="dataAjuste"
      :registro="dataAjuste ? diasPorData[dataAjuste] ?? null : null"
      @salvo="aoSalvarAjuste"
    />

    <!-- Aviso -->
    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-3 opacity-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0"
    >
      <div
        v-if="aviso"
        role="status"
        class="fixed inset-x-4 bottom-4 z-40 mx-auto max-w-md rounded-[3px] px-4 py-3 text-sm font-medium shadow-lg sm:inset-x-auto sm:right-6 sm:bottom-6"
        :class="{
          'bg-carimbo text-cartao': aviso.tipo === 'erro',
          'bg-tinta text-cartao': aviso.tipo === 'ok',
          'bg-credito text-cartao': aviso.tipo === 'pdf',
          'border border-tinta bg-cartao text-tinta': aviso.tipo === 'info',
        }"
      >
        {{ aviso.texto }}
      </div>
    </Transition>
  </div>
</template>
