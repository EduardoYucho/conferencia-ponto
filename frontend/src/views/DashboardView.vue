<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { usePontoStore } from '@/stores/ponto'
import { useAuthStore } from '@/stores/auth'
import { useRelogio } from '@/composables/useRelogio'
import BarraProgressoDiaria from '@/components/BarraProgressoDiaria.vue'
import CartaoMensal from '@/components/CartaoMensal.vue'
import GraficoSaldoAnual from '@/components/GraficoSaldoAnual.vue'
import ModalAjusteBatidas from '@/components/ModalAjusteBatidas.vue'
import ModalCicloBanco from '@/components/ModalCicloBanco.vue'
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
  hoje, jornadaBaseSegundos, diasPorData, registroHoje, diaSelecionado, dataSelecionada,
  saldoMensal, ciclo, marcadoresDoMes, ehMesAtual,
  tempoReal, monitoramento, ultimoEvento,
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
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <!-- Cabeçalho -->
    <header class="flex flex-wrap items-end justify-between gap-x-6 gap-y-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">Banco de horas · jornada 08:48</p>
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
          :jornada-base="registroHoje ? registroHoje.jornadaPrevistaSegundos : jornadaBaseSegundos"
          :em-andamento="registroHoje?.status === 'EM_ANDAMENTO'"
          :sem-jornada="registroHoje ? registroHoje.jornadaPrevistaSegundos === 0 : false"
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
        :grade="configuracao?.grade"
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
        @selecionar="store.selecionarDia"
        @lancar="abrirLancamento"
        @ajustar="abrirAjuste"
      />
    </section>

    <ModalLancamentoManual v-if="auth.podeEscrever" v-model="modalAberto" :data-inicial="dataModal" @salvo="aoSalvarManual" />
    <ModalCicloBanco v-if="auth.podeEscrever" v-model="cicloAberto" :modo="modoCiclo" :ciclo="ciclo" @concluido="aoConcluirCiclo" />
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
