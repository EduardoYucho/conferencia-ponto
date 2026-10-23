<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import ModalAjusteBatidas from '@/components/ModalAjusteBatidas.vue'
import ModalLancamentoManual from '@/components/ModalLancamentoManual.vue'
import { salvarResposta } from '@/utils/download'
import { mensagemDe } from '@/utils/erros'
import { dataBR, dataCurta, diaSemanaCurto, formatarDuracao, formatarSaldo, nomeMes } from '@/utils/tempo'

/**
 * Banco de horas: a carga semanal comparada com o horário, quanto o fim de semana compensou e quanto foi para o
 * banco, e o extrato do mês dia a dia com a origem de cada batida (relógio com o PDF, sem comprovante, ajustada,
 * lançada à mão) e o histórico das mudanças. Quem confere marca o mês como conferido (e ele fica travado).
 * Os sábados e domingos ficam aqui, e não no painel.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const { ultimoEvento, reconectouEm, bancoMudouEm } = storeToRefs(ponto)
const route = useRoute()
const router = useRouter()

// ------------------------------------------------------------------ mês
const agora = new Date()
function mesDoEndereco() {
  const atual = { ano: agora.getFullYear(), mes: agora.getMonth() + 1 }
  const a = route.query.ano === undefined ? atual.ano : Number(route.query.ano)
  const m = route.query.mes === undefined ? atual.mes : Number(route.query.mes)
  const valido = Number.isInteger(a) && a >= 2000 && a <= 2100 && Number.isInteger(m) && m >= 1 && m <= 12
  return valido ? { ano: a, mes: m } : atual
}
const inicial = mesDoEndereco()
const ano = ref(inicial.ano)
const mes = ref(inicial.mes)

function navegar(deslocamento) {
  const alvo = new Date(ano.value, mes.value - 1 + deslocamento, 1)
  ano.value = alvo.getFullYear()
  mes.value = alvo.getMonth() + 1
}
function voltarParaHoje() {
  ano.value = agora.getFullYear()
  mes.value = agora.getMonth() + 1
}
const ehMesAtual = computed(() => ano.value === agora.getFullYear() && mes.value === agora.getMonth() + 1)

// ------------------------------------------------------------------ aviso
const aviso = ref(null)
let timerAviso = null
function mostrarAviso(texto, tipo = 'ok') {
  aviso.value = { texto, tipo }
  clearTimeout(timerAviso)
  if (tipo !== 'erro') timerAviso = setTimeout(() => (aviso.value = null), 5000)
}

// ------------------------------------------------------------------ dados
const dados = ref(null)
const carregando = ref(false)
const erro = ref(null)
let pedido = 0
let timerRecarga = null

async function carregar() {
  const meu = ++pedido
  carregando.value = true
  erro.value = null
  try {
    const resposta = await pontoApi.bancoDeHoras(ano.value, mes.value)
    if (meu === pedido) dados.value = resposta
  } catch (e) {
    if (meu === pedido) erro.value = e
  } finally {
    if (meu === pedido) carregando.value = false
  }
}

watch([ano, mes], () => {
  router.replace({ query: { ...route.query, ano: ano.value, mes: mes.value } })
  dados.value = null
  carregar()
}, { immediate: true })

// a rota é a mesma quando um aviso do sino leva a outro mês
watch(() => [route.query.ano, route.query.mes], () => {
  const alvo = mesDoEndereco()
  if (alvo.ano !== ano.value || alvo.mes !== mes.value) {
    ano.value = alvo.ano
    mes.value = alvo.mes
  }
})

function agendarRecarga() {
  clearTimeout(timerRecarga)
  timerRecarga = setTimeout(carregar, 400)
}
watch(ultimoEvento, (evento) => {
  if (evento?.tipo !== 'jornada-atualizada') return
  const [a, m] = evento.data.split('-').map(Number)
  if (a * 12 + m <= ano.value * 12 + mes.value) agendarRecarga()
})
watch(bancoMudouEm, (momento) => momento && agendarRecarga())
watch(reconectouEm, (momento) => momento && carregar())
onBeforeUnmount(() => {
  clearTimeout(timerRecarga)
  clearTimeout(timerAviso)
})

// ------------------------------------------------------------------ carga e compensação
const horario = computed(() => dados.value?.horario ?? null)
const comp = computed(() => dados.value?.compensacao ?? null)
const temDiferenca = computed(() => !!comp.value && comp.value.diferencaSemanalSegundos !== 0)
const faltaHorario = computed(() => !!comp.value && comp.value.diferencaSemanalSegundos > 0)
/** O que o mês pede de compensação (positivo): o do mês inteiro, ou o que de fato entrou num mês que terminou. */
const aCompensar = computed(() => {
  const c = comp.value
  if (!c) return 0
  const terminou = dados.value?.conferencia?.terminou
  return Math.max(0, -(terminou ? c.diferencaContadaSegundos : c.diferencaPrevistaSegundos))
})
const progresso = computed(() => (aCompensar.value ? Math.min(100, (comp.value.compensadoSegundos / aCompensar.value) * 100) : 0))
const restantes = computed(() => (comp.value?.finsDeSemanaRestantes ?? [])
  .map((b) => (b.inicio === b.fim ? dataCurta(b.inicio) : `${dataCurta(b.inicio)} a ${dataCurta(b.fim)}`)))

const avisosDoHorario = computed(() => horario.value?.avisos ?? [])
const avisosDosDias = computed(() => dados.value?.avisos ?? [])
const NIVEL = {
  ALERTA: { classe: 'border-carimbo/50 bg-carimbo/5', rotulo: 'Fora da lei', cor: 'text-carimbo' },
  AVISO: { classe: 'border-amber-500/50 bg-amber-500/5', rotulo: 'Atenção', cor: 'text-amber-800' },
  INFO: { classe: 'border-linha bg-papel/60', rotulo: 'Para saber', cor: 'text-tinta-suave' },
}

// ------------------------------------------------------------------ extrato
const ORIGEM = {
  relogio: { rotulo: 'relógio', classe: 'bg-credito/10 text-credito', titulo: 'Batida do relógio, com o comprovante em PDF' },
  sem: { rotulo: 'sem comprovante', classe: 'bg-amber-500/15 text-amber-800', titulo: 'Registrada no sistema, sem o PDF do relógio' },
  ajustada: { rotulo: 'ajustada à mão', classe: 'bg-tinta/10 text-tinta', titulo: 'Incluída ou corrigida à mão (veja o histórico do dia)' },
  manual: { rotulo: 'lançada à mão', classe: 'bg-carimbo/10 text-carimbo', titulo: 'Dia sem expediente digitado à mão' },
}
const ORIGEM_DO_HISTORICO = { AJUSTE: 'ajuste das batidas', RH: 'conforme o RH', MANUAL: 'lançamento à mão', EXCLUSAO: 'exclusão do dia' }
const rotuloDoHistorico = (a) =>
  (a.origem === 'AJUSTE' && a.justificativa?.startsWith('Conforme relatório do RH') ? 'conforme o RH' : ORIGEM_DO_HISTORICO[a.origem] ?? 'ajuste')

const FILTROS = [
  { valor: 'FDS', rotulo: 'Fins de semana e lançamentos' },
  { valor: 'MAO', rotulo: 'Lançado ou ajustado à mão' },
  { valor: 'TUDO', rotulo: 'O mês inteiro' },
]
const filtro = ref('FDS')

function analisarDia(d) {
  const r = d.registro
  const pdfPorTipo = Object.fromEntries(d.comprovantes.map((c) => [c.tipoBatida, c]))
  const batidas = r.batidas.filter((b) => b.real).map((b) => ({
    ...b,
    origem: r.registroManual ? 'manual' : pdfPorTipo[b.tipo] ? 'relogio' : b.ajustada ? 'ajustada' : 'sem',
    pdf: pdfPorTipo[b.tipo] ?? null,
  }))
  const fechado = r.status === 'FECHADA'
  return {
    tipo: 'dia',
    chave: `d-${r.data}`,
    data: r.data,
    registro: r,
    semExpediente: d.semExpediente,
    batidas,
    comprovantes: d.comprovantes,
    ajustes: d.ajustes ?? [],
    diferenca: d.diferencaDaCargaSegundos ?? 0,
    fechado,
    incompleto: !fechado && r.data < (dados.value?.hoje ?? ''),
    aMao: r.registroManual || r.ajustado || (d.ajustes ?? []).length > 0,
    valor: (fechado ? r.saldoDiarioSegundos : 0) + (d.diferencaDaCargaSegundos ?? 0),
  }
}

const linhas = computed(() => {
  if (!dados.value) return []
  const dias = dados.value.dias.map(analisarDia)
  const lancamentos = dados.value.lancamentos.map((l) => ({ tipo: 'lancamento', chave: `l-${l.id}`, data: l.data, lancamento: l, valor: l.segundos }))
  const semRegistro = dados.value.semRegistro.map((data) => ({ tipo: 'sem-registro', chave: `s-${data}`, data, valor: 0 }))
  const excluidos = (dados.value.excluidos ?? []).map((a) => ({ tipo: 'excluido', chave: `x-${a.id}`, data: a.data, ajuste: a, valor: 0 }))
  let lista
  if (filtro.value === 'FDS') lista = [...dias.filter((d) => d.semExpediente), ...lancamentos]
  else if (filtro.value === 'MAO') lista = [...dias.filter((d) => d.aMao), ...lancamentos, ...excluidos]
  else lista = [...dias, ...lancamentos, ...semRegistro, ...excluidos]
  return lista.sort((x, y) => x.data.localeCompare(y.data) || x.tipo.localeCompare(y.tipo))
})
const totalDoFiltro = computed(() => linhas.value.reduce((s, l) => s + l.valor, 0))

const resumo = computed(() => dados.value?.resumo ?? null)
const partesDoSaldo = computed(() => {
  const r = resumo.value
  const c = comp.value
  if (!r || !c) return []
  const jornadas = r.saldoMensalSegundos - r.segundosLancados - r.segundosDaCarga
  return [
    { rotulo: 'Dias de semana', valor: jornadas - c.semExpedienteSegundos },
    { rotulo: 'Fins de semana', valor: c.semExpedienteSegundos },
    { rotulo: 'Lançamentos no banco', valor: r.segundosLancados },
    { rotulo: 'Diferença da carga', valor: r.segundosDaCarga },
  ].filter((p, i) => i < 2 || p.valor)
})

// ------------------------------------------------------------------ conferência
const conferencia = computed(() => dados.value?.conferencia ?? null)
const travado = computed(() => !!conferencia.value?.vigente)
const podeEditar = computed(() => auth.podeEscrever && !travado.value)
const observacao = ref('')
const motivoReabertura = ref('')
const reabrindo = ref(false)
const enviandoConferencia = ref(false)
const formatarMomento = (iso) =>
  new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
const ultimoDiaDoMes = computed(() => new Date(ano.value, mes.value, 0).getDate())

async function conferirMes() {
  if (enviandoConferencia.value) return
  enviandoConferencia.value = true
  try {
    await pontoApi.conferirMes(ano.value, mes.value, auth.pessoaEmTela?.login, observacao.value.trim())
  } catch (e) {
    mostrarAviso(mensagemDe(e), 'erro')
    return
  } finally {
    enviandoConferencia.value = false
  }
  observacao.value = ''
  mostrarAviso(`${nomeMes(mes.value)} conferido: o mês ficou travado para mudanças à mão.`)
  carregar()
}

async function reabrirMes() {
  if (enviandoConferencia.value) return
  if (motivoReabertura.value.trim().length < 5) {
    mostrarAviso('Escreva por que o mês vai ser reaberto (pelo menos 5 letras).', 'erro')
    return
  }
  enviandoConferencia.value = true
  try {
    await pontoApi.reabrirMes(ano.value, mes.value, auth.pessoaEmTela?.login, motivoReabertura.value.trim())
  } catch (e) {
    mostrarAviso(mensagemDe(e), 'erro')
    return
  } finally {
    enviandoConferencia.value = false
  }
  motivoReabertura.value = ''
  reabrindo.value = false
  mostrarAviso(`${nomeMes(mes.value)} reaberto: o mês voltou a aceitar mudanças.`)
  carregar()
}

// ------------------------------------------------------------------ ações nos dias
const manualAberto = ref(false)
const dataManual = ref(null)
const registroManual = ref(null)
const ajusteAberto = ref(false)
const dataAjuste = ref(null)
const registroAjuste = ref(null)

function lancarFimDeSemana(linha = null) {
  dataManual.value = linha?.data ?? null
  registroManual.value = linha?.registro ?? null
  manualAberto.value = true
}
function ajustar(linha) {
  if (linha.registro.registroManual) {
    lancarFimDeSemana(linha)
    return
  }
  dataAjuste.value = linha.data
  registroAjuste.value = linha.registro
  ajusteAberto.value = true
}
function aoSalvar(registro) {
  mostrarAviso(`${dataCurta(registro.data)} salvo · saldo do dia ${formatarSaldo(registro.saldoDiarioSegundos)}`)
  carregar()
}

const baixando = ref(new Set())
async function baixar(comprovante) {
  if (baixando.value.has(comprovante.id)) return
  baixando.value = new Set(baixando.value).add(comprovante.id)
  try {
    salvarResposta(await pontoApi.baixarComprovante(comprovante.id), `comprovante_${comprovante.id}.pdf`)
  } catch (e) {
    mostrarAviso(`Não foi possível baixar o comprovante (${comprovante.rotulo}). ${mensagemDe(e)}`, 'erro')
  } finally {
    const restante = new Set(baixando.value)
    restante.delete(comprovante.id)
    baixando.value = restante
  }
}

function classeSaldo(seg) {
  if (seg === null || seg === undefined || seg === 0) return 'text-tinta'
  return seg > 0 ? 'text-credito' : 'text-carimbo'
}
const curto = (seg) => formatarDuracao(seg, { curto: true })
const listaHoras = (horarios) => (horarios.length ? horarios.join(' ') : 'sem batidas')
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div class="min-w-0">
        <p class="rotulo">Banco de horas · carga semanal e fins de semana</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Banco de horas</h1>
        <p v-if="!auth.vendoOsProprios" class="mt-1 font-sans text-lg font-semibold">{{ auth.pessoaEmTela?.nome }}</p>
        <p class="mt-2 max-w-2xl text-sm text-tinta-suave">
          A carga semanal comparada com o horário, o que o fim de semana compensou e cada dia que mexeu no banco, com a
          origem das batidas: do relógio (com o comprovante), sem comprovante, ajustada ou lançada à mão.
        </p>
      </div>
      <button v-if="podeEditar" type="button" class="botao-primario" @click="lancarFimDeSemana()">Lançar fim de semana</button>
    </header>

    <!-- Navegação de mês -->
    <nav class="flex items-center justify-between py-4" aria-label="Navegação entre meses">
      <button type="button" class="botao-secundario px-3!" aria-label="Mês anterior" @click="navegar(-1)">‹</button>
      <p class="text-center">
        <span class="font-sans text-xl font-bold uppercase tracking-[0.2em] [font-stretch:90%]">{{ nomeMes(mes) }}</span>
        <span class="carimbo ml-2 text-xl text-tinta-suave">{{ ano }}</span>
        <button v-if="!ehMesAtual" type="button" class="ml-3 text-xs font-semibold text-carimbo underline underline-offset-4" @click="voltarParaHoje">voltar para hoje</button>
      </p>
      <button type="button" class="botao-secundario px-3!" aria-label="Próximo mês" @click="navegar(1)">›</button>
    </nav>

    <div v-if="erro" class="mb-4">
      <EstadoDaTela :erro="erro" :carregando="carregando" manter @tentar="carregar" />
    </div>
    <p v-if="!dados && !erro" class="py-12 text-center text-sm text-tinta-suave">{{ carregando ? 'Carregando…' : '' }}</p>

    <template v-if="dados">
      <!-- Mês conferido -->
      <p
        v-if="travado"
        role="status"
        class="mb-4 rounded-[3px] border border-credito/40 bg-credito/5 px-4 py-2.5 text-sm"
      >
        <b class="text-credito">Mês conferido</b> por {{ conferencia.vigente.conferidoPor }} em {{ formatarMomento(conferencia.vigente.conferidoEm) }}:
        batidas, lançamentos e folgas de {{ nomeMes(mes).toLowerCase() }} estão travados.
        <template v-if="conferencia.mudou"> <b class="text-amber-800">Os números mudaram depois da conferência.</b></template>
      </p>

      <section class="grid gap-4 lg:grid-cols-[1.1fr_1fr]">
        <!-- Carga semanal x horário -->
        <article class="cartao px-5 py-5" aria-labelledby="titulo-carga">
          <div class="flex flex-wrap items-baseline justify-between gap-2">
            <h2 id="titulo-carga" class="rotulo">Carga semanal x horário</h2>
            <RouterLink
              v-if="auth.vendoOsProprios && auth.podeEscrever"
              :to="{ name: 'conta', hash: '#horario' }"
              class="text-xs font-semibold text-tinta-suave underline underline-offset-4 hover:text-tinta"
            >alterar em Minha conta</RouterLink>
          </div>
          <dl class="mt-4 grid grid-cols-3 gap-3 text-center">
            <div class="rounded-[3px] border border-linha px-2 py-3">
              <dt class="text-xs text-tinta-suave">O horário soma</dt>
              <dd class="carimbo mt-1 text-2xl font-semibold">{{ curto(comp.somaDoHorarioSegundos) }}</dd>
            </div>
            <div class="rounded-[3px] border border-linha px-2 py-3">
              <dt class="text-xs text-tinta-suave">Carga semanal</dt>
              <dd class="carimbo mt-1 text-2xl font-semibold">{{ curto(comp.cargaSemanalSegundos) }}</dd>
            </div>
            <div class="rounded-[3px] border px-2 py-3" :class="temDiferenca ? 'border-carimbo/40' : 'border-linha'">
              <dt class="text-xs text-tinta-suave">{{ faltaHorario ? 'Faltam por semana' : temDiferenca ? 'Sobra por semana' : 'Diferença' }}</dt>
              <dd class="carimbo mt-1 text-2xl font-semibold" :class="faltaHorario ? 'text-carimbo' : temDiferenca ? 'text-credito' : ''">
                {{ curto(Math.abs(comp.diferencaSemanalSegundos)) }}
              </dd>
            </div>
          </dl>
          <p class="mt-3 text-sm text-tinta-suave">
            <template v-if="!comp.cargaInformada">
              A carga é a do horário: as horas do fim de semana vão inteiras para o banco.
            </template>
            <template v-else-if="faltaHorario">
              {{ curto(comp.diferencaSemanalSegundos) }} ÷ {{ comp.diasComExpediente }} dias = <b class="carimbo text-tinta">{{ curto(comp.diferencaPorDiaSegundos) }}</b>
              de débito em cada dia útil fechado. O fim de semana compensa; o que sobrar vai para o banco.
            </template>
            <template v-else-if="temDiferenca">
              O horário passa da carga: cada dia útil fechado leva <b class="carimbo text-credito">{{ curto(-comp.diferencaPorDiaSegundos) }}</b> de crédito ao banco.
            </template>
            <template v-else>O horário fecha a carga: as horas do fim de semana vão inteiras para o banco.</template>
          </p>
          <ul v-if="avisosDoHorario.length" class="mt-3 space-y-2" aria-label="Avisos da lei sobre o horário">
            <li v-for="a in avisosDoHorario" :key="`${a.codigo}-${a.data}`" class="rounded-[3px] border px-3 py-2 text-sm" :class="NIVEL[a.nivel].classe">
              <b :class="NIVEL[a.nivel].cor">{{ NIVEL[a.nivel].rotulo }}:</b> {{ a.mensagem }}
              <span class="text-xs text-tinta-apagada">({{ a.fundamento }})</span>
            </li>
          </ul>
        </article>

        <!-- Compensação do mês -->
        <article class="cartao px-5 py-5" aria-labelledby="titulo-compensacao">
          <h2 id="titulo-compensacao" class="rotulo">{{ faltaHorario ? 'Compensação' : 'Fins de semana' }} · {{ nomeMes(mes).toLowerCase() }}</h2>
          <template v-if="faltaHorario">
            <p class="mt-3 text-sm">
              A compensar no mês: <b class="carimbo">{{ curto(aCompensar) }}</b>
              <span class="text-tinta-suave">({{ dados.conferencia.terminou ? comp.diasUteisContados : comp.diasUteis }} dias úteis × {{ curto(comp.diferencaPorDiaSegundos) }})</span>
            </p>
            <div class="mt-3 h-3 overflow-hidden rounded-full bg-papel-escuro" role="progressbar" :aria-valuenow="Math.round(progresso)" aria-valuemin="0" aria-valuemax="100" :aria-label="`Compensado ${Math.round(progresso)}%`">
              <div class="h-full rounded-full bg-credito transition-all" :style="{ width: `${progresso}%` }" />
            </div>
            <dl class="mt-3 grid grid-cols-3 gap-2 text-sm">
              <div><dt class="text-xs text-tinta-suave">Compensado</dt><dd class="carimbo font-semibold text-credito">{{ curto(comp.compensadoSegundos) }}</dd></div>
              <div><dt class="text-xs text-tinta-suave">Falta</dt><dd class="carimbo font-semibold" :class="comp.faltaSegundos ? 'text-carimbo' : ''">{{ curto(comp.faltaSegundos) }}</dd></div>
              <div><dt class="text-xs text-tinta-suave">Para o banco</dt><dd class="carimbo font-semibold">{{ curto(comp.paraOBancoSegundos) }}</dd></div>
            </dl>
            <p v-if="comp.faltaSegundos && restantes.length" class="mt-3 text-sm text-tinta-suave">
              Fins de semana que ainda vêm: <b class="text-tinta">{{ restantes.join(' · ') }}</b>. Dá para lançar de manhã, à tarde ou no domingo, como for melhor.
            </p>
            <p v-else-if="comp.faltaSegundos" class="mt-3 text-sm text-carimbo">
              O mês terminou faltando {{ curto(comp.faltaSegundos) }}: ficou como débito no banco de horas.
            </p>
            <p v-else class="mt-3 text-sm text-credito">A carga do mês está fechada.</p>
          </template>
          <template v-else>
            <p class="carimbo mt-3 text-3xl font-semibold" :class="classeSaldo(comp.semExpedienteSegundos)">{{ formatarSaldo(comp.semExpedienteSegundos) }}</p>
            <p class="mt-1 text-sm text-tinta-suave">trabalhado em dias sem expediente: vai todo para o banco de horas.</p>
          </template>
          <p v-if="dados.semRegistro.length" class="mt-3 text-xs text-tinta-suave">
            {{ dados.semRegistro.length }} dia(s) útil(eis) sem registro não entram no banco nem na diferença.
          </p>
        </article>
      </section>

      <!-- Saldo do mês, banco e conferência -->
      <section class="mt-4 grid gap-4 md:grid-cols-3">
        <article class="cartao px-5 py-4">
          <h2 class="rotulo">Saldo de {{ nomeMes(mes).toLowerCase() }}</h2>
          <p class="carimbo mt-2 text-3xl font-semibold" :class="classeSaldo(resumo.saldoMensalSegundos)">{{ formatarSaldo(resumo.saldoMensalSegundos) }}</p>
          <dl class="mt-3 space-y-1 text-sm">
            <div v-for="p in partesDoSaldo" :key="p.rotulo" class="flex justify-between gap-2">
              <dt class="text-tinta-suave">{{ p.rotulo }}</dt>
              <dd class="carimbo font-semibold" :class="classeSaldo(p.valor)">{{ formatarSaldo(p.valor) }}</dd>
            </div>
          </dl>
        </article>
        <article class="cartao px-5 py-4">
          <h2 class="rotulo">Banco de horas</h2>
          <p class="carimbo mt-2 text-3xl font-semibold" :class="classeSaldo(dados.bancoDeHoras?.saldoSegundos)">
            {{ dados.bancoDeHoras ? formatarSaldo(dados.bancoDeHoras.saldoSegundos) : '—' }}
          </p>
          <p class="mt-1 text-xs text-tinta-suave">
            <template v-if="!dados.bancoDeHoras">mês fora dos ciclos do banco</template>
            <template v-else>{{ ehMesAtual ? 'agora' : `no fim de ${nomeMes(mes).toLowerCase()}` }} · ciclo desde {{ dataBR(dados.bancoDeHoras.cicloInicio) }}</template>
          </p>
        </article>

        <!-- Conferência -->
        <article class="cartao px-5 py-4" aria-labelledby="titulo-conferencia">
          <h2 id="titulo-conferencia" class="rotulo">Conferência do mês</h2>
          <template v-if="conferencia.vigente">
            <p class="mt-2 text-sm"><b class="text-credito">Conferido</b> por {{ conferencia.vigente.conferidoPor }} em {{ formatarMomento(conferencia.vigente.conferidoEm) }}</p>
            <p v-if="conferencia.vigente.observacao" class="mt-1 text-sm italic">“{{ conferencia.vigente.observacao }}”</p>
            <p v-if="conferencia.mudou" class="mt-2 rounded-[3px] border border-amber-500/50 bg-amber-500/5 px-2 py-1.5 text-xs">
              Mudou depois da conferência: saldo {{ formatarSaldo(conferencia.vigente.saldoMesSegundos) }} → {{ formatarSaldo(conferencia.saldoAgoraSegundos) }},
              {{ conferencia.vigente.diasRegistrados }} → {{ conferencia.diasRegistradosAgora }} dia(s) registrados.
            </p>
            <template v-if="conferencia.podeConferir">
              <button v-if="!reabrindo" type="button" class="botao-secundario mt-3 py-1.5! text-xs" @click="reabrindo = true">Reabrir o mês</button>
              <form v-else class="mt-3 space-y-2" @submit.prevent="reabrirMes">
                <label class="block text-xs text-tinta-suave" for="motivo-reabertura">Por que reabrir?</label>
                <input id="motivo-reabertura" v-model="motivoReabertura" class="campo" maxlength="300" placeholder="Ex.: faltou o atestado do dia 14" />
                <div class="flex gap-2">
                  <button type="submit" class="botao-primario py-1.5! text-xs" :disabled="enviandoConferencia">Reabrir</button>
                  <button type="button" class="botao-secundario py-1.5! text-xs" @click="reabrindo = false">Cancelar</button>
                </div>
              </form>
            </template>
          </template>
          <template v-else>
            <p class="mt-2 text-sm text-tinta-suave">Não conferido.</p>
            <template v-if="conferencia.podeConferir">
              <form v-if="conferencia.terminou" class="mt-3 space-y-2" @submit.prevent="conferirMes">
                <label class="block text-xs text-tinta-suave" for="obs-conferencia">Observação (opcional)</label>
                <input id="obs-conferencia" v-model="observacao" class="campo" maxlength="300" placeholder="Ex.: conferido com o relatório do RH" />
                <button type="submit" class="botao-primario w-full py-1.5! text-xs" :disabled="enviandoConferencia">Marcar como conferido</button>
                <p class="text-xs text-tinta-apagada">O mês fica travado para mudanças à mão até alguém reabrir.</p>
              </form>
              <p v-else class="mt-2 text-xs text-tinta-suave">Dá para conferir depois que o mês terminar ({{ ultimoDiaDoMes }}/{{ String(mes).padStart(2, '0') }}).</p>
            </template>
          </template>
          <details v-if="conferencia.historico.some((c) => c.reabertoEm)" class="mt-3 text-xs">
            <summary class="cursor-pointer font-semibold text-tinta-suave">Histórico</summary>
            <ul class="mt-1 space-y-1">
              <li v-for="c in conferencia.historico" :key="c.id">
                {{ formatarMomento(c.conferidoEm) }} · conferido por {{ c.conferidoPor }}<template v-if="c.reabertoEm">;
                  reaberto por {{ c.reabertoPor }} em {{ formatarMomento(c.reabertoEm) }}: “{{ c.motivoReabertura }}”</template>
              </li>
            </ul>
          </details>
        </article>
      </section>

      <!-- Avisos da lei nos dias -->
      <section v-if="avisosDosDias.length" class="mt-4 cartao px-5 py-4" aria-labelledby="titulo-avisos">
        <h2 id="titulo-avisos" class="rotulo">Avisos da lei nos dias trabalhados</h2>
        <ul class="mt-3 space-y-2">
          <li v-for="a in avisosDosDias" :key="`${a.codigo}-${a.data}`" class="rounded-[3px] border px-3 py-2 text-sm" :class="NIVEL[a.nivel].classe">
            <b :class="NIVEL[a.nivel].cor">{{ NIVEL[a.nivel].rotulo }}:</b> {{ a.mensagem }}
            <span class="text-xs text-tinta-apagada">({{ a.fundamento }})</span>
          </li>
        </ul>
        <p class="mt-2 text-xs text-tinta-apagada">São avisos: acordos e convenções coletivas podem mudar a regra. Quem decide é o RH.</p>
      </section>

      <!-- Extrato -->
      <section class="mt-4 cartao overflow-hidden" aria-labelledby="titulo-extrato">
        <div class="flex flex-wrap items-center justify-between gap-3 border-b border-linha px-5 py-3">
          <h2 id="titulo-extrato" class="rotulo">Extrato de {{ nomeMes(mes).toLowerCase() }}</h2>
          <div class="flex flex-wrap gap-1.5" role="group" aria-label="O que mostrar">
            <button
              v-for="f in FILTROS"
              :key="f.valor"
              type="button"
              class="rounded-full border px-3 py-1 text-xs font-semibold transition"
              :class="filtro === f.valor ? 'border-tinta bg-tinta text-cartao' : 'border-linha text-tinta-suave hover:border-tinta hover:text-tinta'"
              :aria-pressed="filtro === f.valor"
              @click="filtro = f.valor"
            >{{ f.rotulo }}</button>
          </div>
        </div>

        <ul class="divide-y divide-linha/70">
          <li v-if="!linhas.length" class="px-5 py-10 text-center text-sm text-tinta-suave">
            {{ filtro === 'FDS' ? 'Nenhum fim de semana nem lançamento no banco neste mês.' : 'Nada neste mês.' }}
          </li>
          <li v-for="l in linhas" :key="l.chave" class="px-5 py-3" :class="l.tipo === 'dia' && l.semExpediente ? 'bg-papel/50' : ''">
            <!-- dia -->
            <template v-if="l.tipo === 'dia'">
              <div class="flex flex-wrap items-start gap-x-4 gap-y-2">
                <div class="w-28 shrink-0">
                  <p><span class="carimbo font-semibold">{{ dataCurta(l.data) }}</span> <span class="text-xs uppercase" :class="l.semExpediente ? 'text-carimbo' : 'text-tinta-suave'">{{ diaSemanaCurto(l.data) }}</span></p>
                  <p class="mt-0.5 flex flex-wrap gap-1">
                    <span v-if="l.semExpediente" class="rounded-[2px] bg-carimbo/10 px-1 text-[0.6rem] font-bold tracking-wide text-carimbo uppercase">fim de semana</span>
                    <span v-else-if="l.registro.tipoDia === 'FERIADO'" class="rounded-[2px] bg-carimbo/10 px-1 text-[0.6rem] font-bold tracking-wide text-carimbo uppercase">feriado</span>
                    <span v-if="l.incompleto" class="rounded-[2px] bg-carimbo/10 px-1 text-[0.6rem] font-bold tracking-wide text-carimbo uppercase">incompleto</span>
                    <span v-else-if="!l.fechado" class="rounded-[2px] bg-amber-500/15 px-1 text-[0.6rem] font-bold tracking-wide text-amber-800 uppercase">em andamento</span>
                  </p>
                </div>
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap gap-1.5">
                    <span v-for="b in l.batidas" :key="b.tipo" class="inline-flex items-center gap-1 rounded-[3px] border border-linha bg-cartao px-1.5 py-0.5 text-xs" :title="ORIGEM[b.origem].titulo">
                      <span class="carimbo font-medium">{{ b.real }}</span>
                      <span class="rounded-[2px] px-1 text-[0.6rem] font-bold uppercase" :class="ORIGEM[b.origem].classe">{{ ORIGEM[b.origem].rotulo }}</span>
                      <button
                        v-if="b.pdf"
                        type="button"
                        class="font-semibold text-tinta-suave hover:text-tinta disabled:opacity-50"
                        :disabled="baixando.has(b.pdf.id)"
                        :aria-label="`Baixar o comprovante da ${b.rotulo} de ${dataCurta(l.data)}`"
                        :title="`Baixar o comprovante (${b.pdf.nomeOriginal})`"
                        @click="baixar(b.pdf)"
                      >PDF ⤓</button>
                    </span>
                    <span v-if="!l.batidas.length" class="text-xs text-tinta-apagada">sem batidas</span>
                  </div>
                  <details v-if="l.ajustes.length || (l.registro.registroManual && !l.ajustes.length)" class="mt-1.5 text-xs">
                    <summary class="cursor-pointer font-semibold text-tinta-suave hover:text-tinta">
                      {{ l.registro.registroManual ? 'lançado à mão' : 'mudado à mão' }}{{ l.ajustes.length ? ` · histórico (${l.ajustes.length})` : '' }}
                    </summary>
                    <ul class="mt-1 space-y-1">
                      <li v-if="!l.ajustes.length" class="text-tinta-suave">Lançado antes do histórico (sem autor registrado).</li>
                      <li v-for="a in l.ajustes" :key="a.id" class="rounded-[2px] bg-papel px-2 py-1">
                        <span class="text-tinta-suave">{{ formatarMomento(a.ajustadoEm) }} · {{ a.usuario }} · {{ rotuloDoHistorico(a) }}</span>
                        <span class="carimbo block">{{ listaHoras(a.antes) }} → {{ listaHoras(a.depois) }}</span>
                        <span class="block italic">“{{ a.justificativa }}”</span>
                      </li>
                    </ul>
                  </details>
                </div>
                <div class="flex shrink-0 items-start gap-4 text-right text-sm">
                  <div>
                    <p class="text-[0.65rem] text-tinta-apagada uppercase">trab.</p>
                    <p class="carimbo">{{ formatarDuracao(l.registro.segundosTrabalhados, { curto: true }) }}</p>
                  </div>
                  <div>
                    <p class="text-[0.65rem] text-tinta-apagada uppercase">saldo</p>
                    <p class="carimbo font-semibold" :class="classeSaldo(l.registro.saldoDiarioSegundos)">{{ l.fechado ? formatarSaldo(l.registro.saldoDiarioSegundos, { curto: true }) : '—' }}</p>
                  </div>
                  <div v-if="l.diferenca">
                    <p class="text-[0.65rem] text-tinta-apagada uppercase" title="Diferença da carga semanal">carga</p>
                    <p class="carimbo font-semibold" :class="classeSaldo(l.diferenca)">{{ formatarSaldo(l.diferenca, { curto: true }) }}</p>
                  </div>
                  <button
                    v-if="podeEditar"
                    type="button"
                    class="botao-secundario px-2! py-1! text-xs"
                    :aria-label="`${l.registro.registroManual ? 'Corrigir o lançamento' : 'Ajustar as batidas'} de ${dataCurta(l.data)}`"
                    @click="ajustar(l)"
                  >{{ l.registro.registroManual ? 'Corrigir' : 'Ajustar' }}</button>
                </div>
              </div>
            </template>

            <!-- lançamento avulso no banco -->
            <div v-else-if="l.tipo === 'lancamento'" class="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
              <span class="w-28 shrink-0"><span class="carimbo font-semibold">{{ dataCurta(l.data) }}</span> <span class="text-xs uppercase text-tinta-suave">{{ diaSemanaCurto(l.data) }}</span></span>
              <span class="rounded-[2px] bg-tinta/10 px-1.5 text-[0.65rem] font-bold uppercase">lançamento no banco</span>
              <span class="min-w-0 flex-1">{{ l.lancamento.descricao }} <span class="text-xs text-tinta-apagada">· por {{ l.lancamento.criadoPor }} em {{ formatarMomento(l.lancamento.criadoEm) }}</span></span>
              <span class="carimbo font-semibold" :class="classeSaldo(l.valor)">{{ formatarSaldo(l.valor, { curto: true }) }}</span>
            </div>

            <!-- dia excluído (o histórico fica) -->
            <div v-else-if="l.tipo === 'excluido'" class="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-tinta-suave">
              <span class="w-28 shrink-0"><span class="carimbo font-semibold text-tinta">{{ dataCurta(l.data) }}</span> <span class="text-xs uppercase">{{ diaSemanaCurto(l.data) }}</span></span>
              <span class="rounded-[2px] bg-tinta/10 px-1.5 text-[0.65rem] font-bold uppercase text-tinta">dia excluído</span>
              <span class="min-w-0 flex-1">
                tinha <span class="carimbo">{{ listaHoras(l.ajuste.antes) }}</span> · excluído por {{ l.ajuste.usuario }} em {{ formatarMomento(l.ajuste.ajustadoEm) }}
              </span>
            </div>

            <!-- dia útil sem registro -->
            <div v-else class="flex flex-wrap items-center gap-x-4 text-sm text-tinta-suave">
              <span class="w-28 shrink-0"><span class="carimbo font-semibold text-tinta">{{ dataCurta(l.data) }}</span> <span class="text-xs uppercase">{{ diaSemanaCurto(l.data) }}</span></span>
              <span class="rounded-[2px] bg-carimbo/10 px-1.5 text-[0.65rem] font-bold uppercase text-carimbo">ponto não batido</span>
              <span>dia útil sem registro: não entra no banco</span>
            </div>
          </li>
        </ul>
        <div v-if="linhas.length" class="flex flex-wrap items-center justify-between gap-2 border-t-2 border-tinta px-5 py-3 text-sm">
          <span class="rotulo">Total do que está na lista</span>
          <span class="carimbo font-semibold" :class="classeSaldo(totalDoFiltro)">{{ formatarSaldo(totalDoFiltro) }}</span>
        </div>
      </section>

      <p class="mt-3 text-xs text-tinta-suave">
        <b>relógio</b> = batida com o comprovante em PDF (dá para baixar) ·
        <b>sem comprovante</b> = registrada no sistema sem o PDF do relógio ·
        <b>ajustada à mão</b> = incluída ou corrigida, com o motivo no histórico ·
        <b>lançada à mão</b> = dia sem expediente digitado (fim de semana, feriado) ·
        <b>carga</b> = o que a carga semanal levou ao banco no dia útil.
      </p>
    </template>

    <ModalLancamentoManual
      v-if="auth.podeEscrever"
      v-model="manualAberto"
      :data-inicial="dataManual"
      :registro="registroManual"
      @salvo="aoSalvar"
    />
    <ModalAjusteBatidas
      v-if="auth.podeEscrever"
      v-model="ajusteAberto"
      :data="dataAjuste"
      :registro="registroAjuste"
      @salvo="aoSalvar"
    />

    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-3 opacity-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0"
    >
      <div
        v-if="aviso"
        :role="aviso.tipo === 'erro' ? 'alert' : 'status'"
        class="fixed inset-x-4 bottom-4 z-40 mx-auto flex max-w-md items-start gap-3 rounded-[3px] px-4 py-3 text-sm font-medium text-cartao shadow-lg sm:inset-x-auto sm:right-6 sm:bottom-6"
        :class="aviso.tipo === 'erro' ? 'bg-carimbo' : 'bg-credito'"
      >
        <span class="min-w-0 flex-1">{{ aviso.texto }}</span>
        <button v-if="aviso.tipo === 'erro'" type="button" class="shrink-0 font-semibold underline underline-offset-4" @click="aviso = null">fechar</button>
      </div>
    </Transition>
  </div>
</template>
