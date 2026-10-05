<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { salvarResposta } from '@/utils/download'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { dataCurta, dataISO, diaSemanaCurto, formatarDuracao, formatarSaldo, nomeMes } from '@/utils/tempo'

/**
 * Portal da coordenação: grade do mês com horários reais x considerados, cálculo da
 * tolerância, saldo diário e download dos comprovantes PDF arquivados.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const { ultimoEvento, hoje, planilha, reconectouEm } = storeToRefs(ponto)
const route = useRoute()
const router = useRouter()

const TIPOS_BASE = ['ENTRADA_1', 'SAIDA_1', 'ENTRADA_2', 'SAIDA_2']
const ROTULO_CURTO = { ENTRADA_1: 'E1', SAIDA_1: 'S1', ENTRADA_2: 'E2', SAIDA_2: 'S2', ENTRADA_3: 'E3', SAIDA_3: 'S3' }
const ROTULO_TIPO_DIA = { UTIL: 'Útil', FIM_DE_SEMANA: 'Fim de semana', FERIADO: 'Feriado', AUSENCIA: 'Ausência' }
const SITUACOES = [
  { valor: 'TODAS', rotulo: 'Todas' },
  { valor: 'DEBITO', rotulo: 'Com débito' },
  { valor: 'CREDITO', rotulo: 'Com crédito' },
  { valor: 'FORA_TOLERANCIA', rotulo: 'Batida fora da tolerância' },
  { valor: 'EM_ANDAMENTO', rotulo: 'Em andamento / incompleto' },
  { valor: 'AJUSTADO', rotulo: 'Ajustado manualmente' },
  { valor: 'COM_COMPROVANTE', rotulo: 'Com comprovante PDF' },
  { valor: 'SEM_COMPROVANTE', rotulo: 'Sem comprovante PDF' },
]

// ------------------------------------------------------------------ filtros
const agora = new Date()

/** Mês pedido no endereço (?ano=&mes=). Se vier algo que não é um mês de verdade, vale o mês atual. */
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
const tipoDia = ref('TODOS')
const situacao = ref('TODAS')
const ordenacao = ref({ campo: 'data', direcao: 1 })

const anos = computed(() => {
  const atual = agora.getFullYear()
  const lista = Array.from({ length: 5 }, (_, i) => atual - 3 + i)
  // navegando com as setas (ou vindo de um link) o ano em tela pode estar fora da lista: ele entra nela
  if (!lista.includes(ano.value)) lista.push(ano.value)
  return lista.sort((x, y) => x - y)
})

// ------------------------------------------------------------------ dados
/** Dados do mês em tela; null = ainda não chegaram (carregando ou falhou). */
const dados = ref(null)
const carregando = ref(false)
const erro = ref(null)
const baixando = ref(new Set())
/** { texto, tipo: 'ok' | 'erro' } */
const aviso = ref(null)
let timerRecarga = null
let timerAviso = null
let pedido = 0

async function carregar() {
  // trocando de mês depressa, as respostas podem chegar fora de ordem: só a do último pedido entra na tela
  const meu = ++pedido
  carregando.value = true
  erro.value = null
  try {
    const resposta = await pontoApi.auditoria(ano.value, mes.value)
    if (meu === pedido) dados.value = resposta
  } catch (e) {
    if (meu === pedido) erro.value = e
  } finally {
    if (meu === pedido) carregando.value = false
  }
}

watch([ano, mes], () => {
  router.replace({ query: { ...route.query, ano: ano.value, mes: mes.value } })
  dados.value = null // os dias do mês anterior não ficam sob o título do mês novo
  carregar()
}, { immediate: true })

function navegar(deslocamento) {
  const alvo = new Date(ano.value, mes.value - 1 + deslocamento, 1)
  ano.value = alvo.getFullYear()
  mes.value = alvo.getMonth() + 1
}

// Tempo real: um PDF importado ou um lançamento no mês em tela recarrega a grade
watch(ultimoEvento, (evento) => {
  if (evento?.tipo !== 'jornada-atualizada') return
  const [a, m] = evento.data.split('-').map(Number)
  if (a !== ano.value || m !== mes.value) return
  clearTimeout(timerRecarga)
  timerRecarga = setTimeout(carregar, 400)
  if (evento.origem === 'COMPROVANTE_PDF') mostrarAviso(`Atualizado agora: ${evento.mensagem}`)
})

// A conexão voltou depois de uma queda: o que mudou nesse meio-tempo não chegou por evento
watch(reconectouEm, (momento) => momento && carregar())

onBeforeUnmount(() => {
  clearTimeout(timerRecarga)
  clearTimeout(timerAviso)
})

/** Aviso no canto da tela. O de erro fica até ser fechado (ou até o próximo aviso); o de sucesso some sozinho. */
function mostrarAviso(texto, tipo = 'ok') {
  aviso.value = { texto, tipo }
  clearTimeout(timerAviso)
  if (tipo !== 'erro') timerAviso = setTimeout(() => (aviso.value = null), 5000)
}

function fecharAviso() {
  clearTimeout(timerAviso)
  aviso.value = null
}

// ------------------------------------------------------------------ linhas
function analisar({ registro, comprovantes, ajustes = [] }) {
  const util = registro.tipoDia === 'UTIL'
  // segundos absorvidos pela tolerância (atraso perdoado ou saída antecipada perdoada)
  const abonado = registro.batidas
    .filter((b) => b.toleranciaAplicada && b.desvioSegundos)
    .reduce((soma, b) => soma + Math.abs(b.desvioSegundos), 0)
  const fora = util ? registro.batidas.filter((b) => b.real && b.oficial && !b.toleranciaAplicada).length : 0
  return {
    ...registro,
    util,
    abonado,
    fora,
    comprovantes,
    pdfPorTipo: Object.fromEntries(comprovantes.map((c) => [c.tipoBatida, c])),
    fechado: registro.status === 'FECHADA',
    /** Dia que já passou com entrada sem saída: faltou batida. */
    incompleto: registro.status === 'EM_ANDAMENTO' && registro.data < hoje.value,
    ajustes,
    batidasAjustadas: registro.batidas.filter((b) => b.ajustada).length,
  }
}

const linhas = computed(() => (dados.value?.dias ?? []).map(analisar))

/** 3ª entrada/saída só aparecem quando algum dia do mês tem mais de 4 batidas. */
const TIPOS = computed(() => {
  const usaTerceiro = linhas.value.some((l) => l.batidas.some((b) => b.tipo.endsWith('_3') && b.real))
  return usaTerceiro ? [...TIPOS_BASE, 'ENTRADA_3', 'SAIDA_3'] : TIPOS_BASE
})
const batidasVisiveis = (linha) => TIPOS.value.map((t) => linha.batidas.find((b) => b.tipo === t))

const filtradas = computed(() => {
  const filtro = {
    TODAS: () => true,
    DEBITO: (l) => l.fechado && l.saldoDiarioSegundos < 0,
    CREDITO: (l) => l.fechado && l.saldoDiarioSegundos > 0,
    FORA_TOLERANCIA: (l) => l.fora > 0,
    EM_ANDAMENTO: (l) => !l.fechado,
    AJUSTADO: (l) => l.ajustado || l.ajustes.length > 0,
    COM_COMPROVANTE: (l) => l.comprovantes.length > 0,
    SEM_COMPROVANTE: (l) => l.comprovantes.length === 0,
  }[situacao.value]
  const { campo, direcao } = ordenacao.value
  const chave = {
    data: (l) => l.data,
    saldo: (l) => l.saldoDiarioSegundos ?? Number.NEGATIVE_INFINITY,
    trabalhado: (l) => l.segundosTrabalhados,
  }[campo]
  return linhas.value
    .filter((l) => tipoDia.value === 'TODOS' || l.tipoDia === tipoDia.value)
    .filter(filtro)
    .sort((x, y) => (chave(x) > chave(y) ? 1 : chave(x) < chave(y) ? -1 : 0) * direcao)
})

const totais = computed(() => {
  const fechadas = filtradas.value.filter((l) => l.fechado)
  return {
    trabalhado: fechadas.reduce((s, l) => s + l.segundosTrabalhados, 0),
    previsto: fechadas.reduce((s, l) => s + l.jornadaPrevistaSegundos, 0),
    saldo: fechadas.reduce((s, l) => s + l.saldoDiarioSegundos, 0),
    abonado: filtradas.value.reduce((s, l) => s + l.abonado, 0),
    fora: filtradas.value.reduce((s, l) => s + l.fora, 0),
    pdfs: filtradas.value.reduce((s, l) => s + l.comprovantes.length, 0),
    abertas: filtradas.value.length - fechadas.length,
  }
})

const indicadores = computed(() => {
  const r = dados.value?.resumo
  // sem os dados do mês (carregando ou falhou) os números não são zero: são desconhecidos ("—")
  const tem = !!dados.value
  return [
    {
      rotulo: `Saldo de ${nomeMes(mes.value).toLowerCase()}`,
      valor: formatarSaldo(r?.saldoMensalSegundos),
      saldo: r?.saldoMensalSegundos ?? null,
      detalhe: r?.segundosLancados ? `inclui ${formatarSaldo(r.segundosLancados)} lançado(s) no banco` : undefined,
    },
    { rotulo: `Acumulado ${ano.value}`, valor: formatarSaldo(r?.saldoAnualAcumuladoSegundos), saldo: r?.saldoAnualAcumuladoSegundos ?? null },
    {
      rotulo: 'Dias registrados',
      valor: tem ? String(linhas.value.length) : '—',
      detalhe: !tem ? undefined : r?.diasEmAberto ? `${r.diasEmAberto} em andamento` : 'todos fechados',
    },
    {
      rotulo: 'Abonado pela tolerância',
      valor: tem ? formatarDuracao(linhas.value.reduce((s, l) => s + l.abonado, 0)) : '—',
      detalhe: tem ? `${linhas.value.reduce((s, l) => s + l.fora, 0)} batida(s) fora` : undefined,
    },
    {
      rotulo: 'Comprovantes PDF',
      valor: tem ? String(linhas.value.reduce((s, l) => s + l.comprovantes.length, 0)) : '—',
      detalhe: !tem ? undefined : ajustadasNoMes.value ? `${ajustadasNoMes.value} batida(s) ajustada(s) à mão` : 'arquivados no mês',
    },
  ]
})

const ajustadasNoMes = computed(() => linhas.value.reduce((s, l) => s + l.batidasAjustadas, 0))

const formatarMomento = (iso) =>
  new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
const listaHoras = (horarios) => (horarios.length ? horarios.join(' ') : 'sem batidas')

function ordenarPor(campo) {
  ordenacao.value = ordenacao.value.campo === campo
    ? { campo, direcao: -ordenacao.value.direcao }
    : { campo, direcao: campo === 'data' ? 1 : -1 }
}

function indicadorOrdem(campo) {
  if (ordenacao.value.campo !== campo) return ''
  return ordenacao.value.direcao > 0 ? '▲' : '▼'
}

function ariaOrdem(campo) {
  if (ordenacao.value.campo !== campo) return 'none'
  return ordenacao.value.direcao > 0 ? 'ascending' : 'descending'
}

function classeSaldo(seg) {
  if (seg === null || seg === undefined) return 'text-tinta-apagada'
  if (seg > 0) return 'text-credito'
  if (seg < 0) return 'text-carimbo'
  return 'text-tinta'
}

// ------------------------------------------------------------------ downloads
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

async function baixarDoDia(linha) {
  for (const comprovante of linha.comprovantes) {
    await baixar(comprovante)
    await new Promise((resolve) => setTimeout(resolve, 350)) // o navegador aceita downloads em sequência
  }
}

// ------------------------------------------------------------------ planilha de conferência
const exportando = ref(false)
ponto.carregarPlanilha()

/** Excel com todos os meses (dia a dia, com os totais) e o resumo do banco de horas. */
async function exportarExcel() {
  if (exportando.value) return
  exportando.value = true
  try {
    salvarResposta(await pontoApi.exportarPlanilha(), `conferencia-ponto-${auth.pessoaEmTela?.login ?? 'eu'}-${dataISO()}.xlsx`)
  } catch (e) {
    mostrarAviso(`Não foi possível exportar. ${mensagemDe(e)}`, 'erro')
  } finally {
    exportando.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-4 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div>
        <p class="rotulo">{{ auth.somenteLeitura ? 'Consulta · somente leitura' : 'Auditoria' }}</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">
          Auditoria do Ponto
        </h1>
        <p v-if="!auth.vendoOsProprios" class="mt-1 font-sans text-lg font-semibold">{{ auth.pessoaEmTela?.nome }}</p>
        <p class="mt-2 max-w-xl text-sm text-tinta-suave">
          Horários reais e considerados (com segundos, como no RH), tolerância do horário de trabalho, saldo diário e comprovantes originais de cada batida.
        </p>
      </div>
      <div class="flex flex-col items-end gap-1.5">
        <div class="flex flex-wrap justify-end gap-2">
          <a
            v-if="planilha?.url"
            :href="planilha.url"
            target="_blank"
            rel="noopener"
            class="botao-secundario"
            :title="planilha.situacao === 'ERRO' ? planilha.erro : 'Planilha do Google atualizada automaticamente'"
          >
            <span class="size-2 rounded-full" :class="planilha.situacao === 'SINCRONIZADA' ? 'bg-credito' : 'bg-amber-500'" aria-hidden="true" />
            Planilha no Google ↗
          </a>
          <button type="button" class="botao-secundario" :disabled="exportando" title="Todos os meses, dia a dia, com os totais e o banco de horas" @click="exportarExcel">
            <svg viewBox="0 0 20 20" class="size-4" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M10 3v10m0 0-4-4m4 4 4-4M4 16h12" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            {{ exportando ? 'Gerando…' : 'Exportar Excel' }}
          </button>
        </div>
        <RouterLink
          v-if="planilha && !planilha.url && auth.vendoOsProprios && auth.ehTitular"
          :to="{ name: 'conta', hash: '#planilha' }"
          class="text-xs text-tinta-suave underline underline-offset-4 hover:text-tinta"
        >Manter uma planilha no Google sempre atualizada</RouterLink>
      </div>
    </header>

    <!-- Filtros -->
    <section class="cartao mt-6 flex flex-wrap items-end gap-x-5 gap-y-4 px-5 py-4" aria-label="Filtros">
      <div class="flex items-end gap-2">
        <button type="button" class="botao-secundario px-3!" aria-label="Mês anterior" @click="navegar(-1)">‹</button>
        <label class="flex flex-col">
          <span class="rotulo">Mês</span>
          <select v-model.number="mes" class="campo mt-1 w-36 font-sans">
            <option v-for="m in 12" :key="m" :value="m">{{ nomeMes(m) }}</option>
          </select>
        </label>
        <label class="flex flex-col">
          <span class="rotulo">Ano</span>
          <select v-model.number="ano" class="campo mt-1 w-24">
            <option v-for="a in anos" :key="a" :value="a">{{ a }}</option>
          </select>
        </label>
        <button type="button" class="botao-secundario px-3!" aria-label="Próximo mês" @click="navegar(1)">›</button>
      </div>
      <label class="flex flex-col">
        <span class="rotulo">Tipo de dia</span>
        <select v-model="tipoDia" class="campo mt-1 w-40 font-sans">
          <option value="TODOS">Todos</option>
          <option value="UTIL">Dias úteis</option>
          <option value="FIM_DE_SEMANA">Fins de semana</option>
          <option value="FERIADO">Feriados</option>
          <option value="AUSENCIA">Férias, folgas e atestados</option>
        </select>
      </label>
      <label class="flex flex-col">
        <span class="rotulo">Situação</span>
        <select v-model="situacao" class="campo mt-1 w-56 font-sans">
          <option v-for="s in SITUACOES" :key="s.valor" :value="s.valor">{{ s.rotulo }}</option>
        </select>
      </label>
      <p class="ml-auto self-center text-sm text-tinta-suave" aria-live="polite">
        {{ dados ? `${filtradas.length} de ${linhas.length} dia(s)` : '' }}
      </p>
    </section>

    <!-- Indicadores -->
    <section class="mt-4 grid grid-cols-2 gap-3 md:grid-cols-5" aria-label="Indicadores do mês">
      <div v-for="i in indicadores" :key="i.rotulo" class="cartao px-4 py-3">
        <p class="rotulo">{{ i.rotulo }}</p>
        <p class="carimbo mt-1 text-2xl font-semibold" :class="i.saldo !== undefined ? classeSaldo(i.saldo) : ''">{{ i.valor }}</p>
        <p v-if="i.detalhe" class="text-xs text-tinta-suave">{{ i.detalhe }}</p>
      </div>
    </section>

    <!-- Falha ao carregar o mês (numa recarga, os dias que já estavam na tela continuam) -->
    <div v-if="erro" class="mt-4">
      <EstadoDaTela :erro="erro" :carregando="carregando" manter @tentar="carregar" />
    </div>

    <!-- Grade -->
    <section class="cartao mt-4 overflow-hidden" aria-label="Grade de auditoria">
      <div class="max-h-[70vh] overflow-auto">
        <table class="w-full border-collapse text-sm" :class="TIPOS.length > 4 ? 'min-w-[82rem]' : 'min-w-[72rem]'">
          <caption class="sr-only">Batidas, tolerância, saldo e comprovantes de {{ nomeMes(mes) }} de {{ ano }}</caption>
          <thead class="sticky top-0 z-10 bg-cartao shadow-[0_2px_0_var(--color-tinta)]">
            <tr class="text-left">
              <th scope="col" class="px-3 py-2.5" :aria-sort="ariaOrdem('data')">
                <button type="button" class="rotulo hover:text-tinta" @click="ordenarPor('data')">Data {{ indicadorOrdem('data') }}</button>
              </th>
              <th v-for="t in TIPOS" :key="t" scope="col" class="rotulo px-2 py-2.5 text-center">{{ ROTULO_CURTO[t] }}</th>
              <th scope="col" class="px-2 py-2.5 text-right" :aria-sort="ariaOrdem('trabalhado')">
                <button type="button" class="rotulo hover:text-tinta" @click="ordenarPor('trabalhado')">Trab. {{ indicadorOrdem('trabalhado') }}</button>
              </th>
              <th scope="col" class="rotulo px-2 py-2.5 text-right">Prev.</th>
              <th scope="col" class="px-2 py-2.5 text-right" :aria-sort="ariaOrdem('saldo')">
                <button type="button" class="rotulo hover:text-tinta" @click="ordenarPor('saldo')">Saldo {{ indicadorOrdem('saldo') }}</button>
              </th>
              <th scope="col" class="rotulo px-2 py-2.5">Tolerância</th>
              <th scope="col" class="rotulo px-3 py-2.5 text-right">Comprovantes</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!dados">
              <td :colspan="TIPOS.length + 6" class="px-3 py-10 text-center text-tinta-suave">
                {{ carregando ? 'Carregando…' : 'Os dias deste mês não foram carregados.' }}
              </td>
            </tr>
            <tr v-else-if="!filtradas.length">
              <td :colspan="TIPOS.length + 6" class="px-3 py-10 text-center text-tinta-suave">
                {{ linhas.length ? 'Nenhum dia atende aos filtros.' : 'Sem registros neste mês.' }}
              </td>
            </tr>
            <tr
              v-for="l in filtradas"
              :key="l.data"
              class="border-b border-linha/80 align-top transition-colors hover:bg-papel/70"
              :class="{ 'bg-papel/50': !l.util, 'shadow-[inset_3px_0_0_var(--color-carimbo)]': l.data === hoje }"
            >
              <td class="px-3 py-2.5 whitespace-nowrap">
                <span class="carimbo font-semibold">{{ dataCurta(l.data) }}</span>
                <span class="ml-1.5 text-xs uppercase" :class="l.util ? 'text-tinta-suave' : 'text-carimbo'">{{ diaSemanaCurto(l.data) }}</span>
                <div class="mt-1 flex flex-wrap gap-1">
                  <span v-if="!l.util" class="rounded-[2px] bg-carimbo/10 px-1 text-[0.6rem] font-bold tracking-wide text-carimbo uppercase">{{ ROTULO_TIPO_DIA[l.tipoDia] }}</span>
                  <span v-if="l.registroManual" class="rounded-[2px] bg-tinta/10 px-1 text-[0.6rem] font-bold tracking-wide text-tinta-suave uppercase">manual</span>
                  <span v-if="l.incompleto" class="rounded-[2px] bg-carimbo/10 px-1 text-[0.6rem] font-bold tracking-wide text-carimbo uppercase" title="Faltou batida neste dia">incompleto</span>
                  <span v-else-if="!l.fechado" class="rounded-[2px] bg-amber-500/15 px-1 text-[0.6rem] font-bold tracking-wide text-amber-800 uppercase">em andamento</span>
                </div>
                <details v-if="l.ajustes.length" class="mt-1 text-xs">
                  <summary class="cursor-pointer font-semibold text-tinta-suave hover:text-tinta">
                    {{ l.ajustes.every((a) => a.justificativa.startsWith('Conforme relatório do RH')) ? 'conforme RH' : 'ajustado à mão' }}{{ l.ajustes.length > 1 ? ` (${l.ajustes.length}×)` : '' }}
                  </summary>
                  <ul class="mt-1 w-60 space-y-1.5 whitespace-normal">
                    <li v-for="a in l.ajustes" :key="a.id" class="rounded-[2px] bg-papel px-2 py-1.5">
                      <span class="block text-tinta-suave">{{ formatarMomento(a.ajustadoEm) }} · {{ a.usuario }}</span>
                      <span class="carimbo block">{{ listaHoras(a.antes) }} → {{ listaHoras(a.depois) }}</span>
                      <span class="block italic">“{{ a.justificativa }}”</span>
                    </li>
                  </ul>
                </details>
              </td>

              <td v-for="(b, i) in batidasVisiveis(l)" :key="b.tipo" class="px-2 py-2.5 text-center">
                <template v-if="b.real">
                  <span
                    class="carimbo block font-medium"
                    :class="l.util && b.oficial && !b.toleranciaAplicada ? 'font-semibold text-carimbo' : 'text-tinta'"
                    :title="b.oficial ? `oficial ${b.oficial} · desvio ${formatarSaldo(b.desvioSegundos)}` : ''"
                  >{{ b.real }}</span>
                  <span class="carimbo block text-xs text-tinta-suave">→ {{ b.considerado }}</span>
                  <button
                    v-if="l.pdfPorTipo[b.tipo]"
                    type="button"
                    class="mt-1 inline-flex items-center gap-1 rounded-[2px] border border-linha px-1.5 py-0.5 text-[0.65rem] font-semibold text-tinta-suave hover:border-tinta hover:text-tinta disabled:opacity-50"
                    :disabled="baixando.has(l.pdfPorTipo[b.tipo].id)"
                    :title="`Baixar comprovante (${l.pdfPorTipo[b.tipo].nomeOriginal})`"
                    :aria-label="`Baixar comprovante da ${b.rotulo} de ${dataCurta(l.data)}`"
                    @click="baixar(l.pdfPorTipo[b.tipo])"
                  >PDF ⤓</button>
                  <span
                    v-else-if="b.ajustada"
                    class="mt-1 inline-block rounded-[2px] bg-tinta/10 px-1.5 py-0.5 text-[0.65rem] font-semibold text-tinta-suave"
                    title="Batida incluída ou corrigida manualmente (sem comprovante): veja o motivo no histórico do dia"
                  >ajustada</span>
                </template>
                <span v-else class="carimbo text-tinta-apagada" :aria-label="`${b.rotulo} sem batida`">--:--:--</span>
              </td>

              <td class="carimbo px-2 py-2.5 text-right">{{ formatarDuracao(l.segundosTrabalhados) }}</td>
              <td class="carimbo px-2 py-2.5 text-right text-tinta-suave">{{ formatarDuracao(l.jornadaPrevistaSegundos) }}</td>
              <td class="carimbo px-2 py-2.5 text-right font-semibold whitespace-nowrap" :class="classeSaldo(l.saldoDiarioSegundos)">
                {{ l.fechado ? formatarSaldo(l.saldoDiarioSegundos) : '—' }}
              </td>
              <td class="px-2 py-2.5 text-xs whitespace-nowrap">
                <template v-if="l.util">
                  <span class="block text-tinta-suave">abonado <b class="carimbo text-tinta">{{ formatarDuracao(l.abonado) }}</b></span>
                  <span v-if="l.fora" class="mt-0.5 inline-block rounded-[2px] bg-carimbo/10 px-1 font-semibold text-carimbo">{{ l.fora }} fora</span>
                </template>
                <span v-else class="text-tinta-apagada">sem grade</span>
              </td>
              <td class="px-3 py-2.5 text-right">
                <button
                  v-if="l.comprovantes.length"
                  type="button"
                  class="botao-secundario px-2.5! py-1! text-xs"
                  :aria-label="`Baixar os ${l.comprovantes.length} comprovante(s) de ${dataCurta(l.data)}`"
                  @click="baixarDoDia(l)"
                >
                  <svg viewBox="0 0 20 20" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                    <path d="M10 3v10m0 0-4-4m4 4 4-4M4 16h12" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  {{ l.comprovantes.length }} PDF{{ l.comprovantes.length > 1 ? 's' : '' }}
                </button>
                <span v-else class="text-xs text-tinta-apagada">—</span>
              </td>
            </tr>
          </tbody>
          <tfoot v-if="filtradas.length" class="sticky bottom-0 bg-cartao shadow-[0_-2px_0_var(--color-tinta)]">
            <tr class="text-sm">
              <th scope="row" class="rotulo px-3 py-2.5 text-left">Total filtrado</th>
              <td :colspan="TIPOS.length" class="px-2 py-2.5 text-xs text-tinta-suave">
                <template v-if="totais.abertas">{{ totais.abertas }} dia(s) em andamento fora dos totais</template>
              </td>
              <td class="carimbo px-2 py-2.5 text-right font-semibold">{{ formatarDuracao(totais.trabalhado) }}</td>
              <td class="carimbo px-2 py-2.5 text-right text-tinta-suave">{{ formatarDuracao(totais.previsto) }}</td>
              <td class="carimbo px-2 py-2.5 text-right font-semibold" :class="classeSaldo(totais.saldo)">{{ formatarSaldo(totais.saldo) }}</td>
              <td class="px-2 py-2.5 text-xs">
                <b class="carimbo">{{ formatarDuracao(totais.abonado) }}</b>
                <span v-if="totais.fora" class="text-carimbo"> · {{ totais.fora }} fora</span>
              </td>
              <td class="carimbo px-3 py-2.5 text-right text-xs">{{ totais.pdfs }} PDF(s)</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>

    <p class="mt-3 text-xs text-tinta-suave">
      <span class="carimbo text-tinta">08:02:31</span> = batida real · <span class="carimbo">→ 08:00:00</span> = horário considerado ·
      <span class="carimbo font-semibold text-carimbo">08:05:22</span> = fora da tolerância (vale o horário exato, com segundos) ·
      abonado = tempo absorvido pela tolerância no dia ·
      <b>ajustada</b> = batida incluída ou corrigida manualmente (sem comprovante; motivo em “ajustado à mão”).
    </p>

    <!-- Lançamentos avulsos no banco de horas (não mudam as batidas; entram no saldo do mês) -->
    <section v-if="dados?.lancamentos?.length" class="cartao mt-6 overflow-hidden" aria-label="Lançamentos no banco de horas">
      <h2 class="rotulo border-b border-linha px-5 py-3">Lançamentos no banco de horas · {{ nomeMes(mes) }} {{ ano }}</h2>
      <ul class="divide-y divide-linha/70">
        <li v-for="l in dados.lancamentos" :key="l.id" class="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-2.5 text-sm">
          <span class="carimbo w-28 font-semibold">{{ diaSemanaCurto(l.data) }} {{ dataCurta(l.data) }}</span>
          <span class="carimbo w-24 font-semibold" :class="classeSaldo(l.segundos)">{{ formatarSaldo(l.segundos) }}</span>
          <span class="min-w-0 flex-1">{{ l.descricao }}</span>
          <span class="text-xs text-tinta-apagada">por {{ l.criadoPor }} em {{ formatarMomento(l.criadoEm) }}</span>
        </li>
      </ul>
    </section>

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
        <!-- o erro não some sozinho: fica até a pessoa ler e fechar -->
        <button v-if="aviso.tipo === 'erro'" type="button" class="shrink-0 font-semibold underline underline-offset-4" @click="fecharAviso">fechar</button>
      </div>
    </Transition>
  </div>
</template>
