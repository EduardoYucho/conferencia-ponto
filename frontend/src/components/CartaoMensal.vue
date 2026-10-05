<script setup>
import { computed } from 'vue'
import { dataCurta, diaSemanaCurto, diasDoMes, ehFimDeSemana, formatarDuracao, formatarSaldo } from '@/utils/tempo'

/** Espelho do mês no formato de um cartão de ponto: uma linha por dia. */
const props = defineProps({
  ano: { type: Number, required: true },
  mes: { type: Number, required: true },
  diasPorData: { type: Object, required: true },
  hoje: { type: String, required: true },
  selecionada: { type: String, default: null },
  /** { data, chave }: realça a linha recém-alterada (a chave reinicia a animação). */
  destacada: { type: Object, default: null },
  /** ROLE_VIEWER: fins de semana sem registro não abrem o lançamento manual. */
  somenteLeitura: { type: Boolean, default: false },
  /** data -> { tipo: 'ausencia' | 'feriado', rotulo }: rotula dias sem registro (férias, feriado...). */
  marcadores: { type: Object, default: () => ({}) },
  /** data -> lançamentos avulsos no banco de horas daquele dia. */
  lancamentos: { type: Object, default: () => ({}) },
  /** data -> expediente previsto pelo horário da pessoa (dia ausente = sem expediente); null = seg–sex. */
  expedientes: { type: Object, default: null },
})
const emit = defineEmits(['selecionar', 'lancar', 'ajustar', 'marcar'])

/** Até 3 intervalos por dia: as colunas da 3ª entrada/saída só aparecem se algum dia do mês usar. */
const colunas = computed(() => {
  const usaTerceiro = Object.values(props.diasPorData).some((r) => r.batidas.some((b) => b.tipo.endsWith('_3') && b.real))
  const tipos = ['ENTRADA_1', 'SAIDA_1', 'ENTRADA_2', 'SAIDA_2']
  return usaTerceiro ? [...tipos, 'ENTRADA_3', 'SAIDA_3'] : tipos
})
const ROTULOS = { ENTRADA_1: 'Entrada 1', SAIDA_1: 'Saída 1', ENTRADA_2: 'Entrada 2', SAIDA_2: 'Saída 2', ENTRADA_3: 'Entrada 3', SAIDA_3: 'Saída 3' }

const batidasDaLinha = (registro) => colunas.value.map((tipo) => registro.batidas.find((b) => b.tipo === tipo))

const linhas = computed(() =>
  diasDoMes(props.ano, props.mes).map((data) => {
    const registro = props.diasPorData[data] ?? null
    const marcador = props.marcadores[data] ?? null
    return {
      data,
      registro,
      marcador,
      fds: registro
        ? registro.tipoDia !== 'UTIL'
        : (props.expedientes ? !props.expedientes[data] : ehFimDeSemana(data)) || !!marcador,
      futuro: data > props.hoje,
      hoje: data === props.hoje,
      /** Dia que já passou com entrada sem saída: faltou batida (não está "em andamento"). */
      incompleto: registro?.status === 'EM_ANDAMENTO' && data < props.hoje,
    }
  }),
)

function classeBatida(registro, b) {
  if (!b.real) return 'text-tinta-apagada'
  // batida extra (sem horário na grade, ex.: saída às 11:01 e volta às 11:15) não tem tolerância: tinta normal
  return registro.tipoDia === 'UTIL' && b.oficial && !b.toleranciaAplicada ? 'text-carimbo font-semibold' : 'text-tinta'
}

function classeSaldo(seg) {
  if (seg === null || seg === undefined) return 'text-tinta-apagada'
  if (seg > 0) return 'text-credito'
  if (seg < 0) return 'text-carimbo'
  return 'text-tinta'
}

const podeLancar = (linha) => !props.somenteLeitura && !linha.registro && linha.fds && !linha.futuro && !linha.marcador

/** Folga, feriado ou justificativa: qualquer dia (inclusive futuro, para planejar férias). */
const podeMarcar = () => !props.somenteLeitura

/** Ajuste manual: dias com batidas (exceto lançamento de fim de semana) e dias úteis sem nenhum registro. */
const podeAjustar = (linha) =>
  !props.somenteLeitura && !linha.futuro && (linha.registro ? !linha.registro.registroManual : !linha.fds)

function tituloBatida(b) {
  if (!b.real) return `${b.rotulo}: sem batida`
  const base = `${b.rotulo}: real ${b.real} · considerado ${b.considerado}`
  return b.ajustada ? `${base} · incluída/corrigida manualmente` : base
}

function aoAtivar(linha) {
  if (linha.registro) emit('selecionar', linha.data)
  else if (podeLancar(linha)) emit('lancar', linha.data)
  else if (podeMarcar()) emit('marcar', linha.data)
}

const clicavel = (linha) => !!linha.registro || podeLancar(linha) || podeMarcar()

const totalLancado = (data) => (props.lancamentos[data] ?? []).reduce((soma, l) => soma + l.segundos, 0)
const tituloLancamentos = (data) =>
  (props.lancamentos[data] ?? []).map((l) => `${formatarSaldo(l.segundos, { curto: true })} · ${l.descricao}`).join('\n')
</script>

<template>
  <div class="overflow-x-auto">
    <table class="w-full border-collapse text-sm" :class="colunas.length > 4 ? 'min-w-[56rem]' : 'min-w-[46rem]'">
      <thead>
        <tr class="border-b-2 border-tinta text-left">
          <th scope="col" class="rotulo py-2 pr-3 pl-2">Dia</th>
          <th v-for="tipo in colunas" :key="tipo" scope="col" class="rotulo px-2 py-2 text-center">{{ ROTULOS[tipo] }}</th>
          <th scope="col" class="rotulo px-2 py-2 text-right">Trab.</th>
          <th scope="col" class="rotulo py-2 pr-2 pl-2 text-right">Saldo</th>
          <th v-if="!somenteLeitura" scope="col" class="w-16 py-2"><span class="sr-only">Ações do dia</span></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="l in linhas"
          :key="destacada?.data === l.data ? `${l.data}-${destacada.chave}` : l.data"
          class="group border-b border-linha/80 transition-colors"
          :class="[
            l.data === selecionada ? 'bg-papel-escuro' : l.fds ? 'bg-papel/70' : '',
            clicavel(l) ? 'cursor-pointer hover:bg-papel-escuro/70' : '',
            l.futuro ? 'opacity-45' : '',
            destacada?.data === l.data ? 'realce' : '',
          ]"
          :tabindex="clicavel(l) ? 0 : -1"
          :aria-selected="l.data === selecionada"
          @click="aoAtivar(l)"
          @keydown.enter.prevent="aoAtivar(l)"
        >
          <td class="relative py-1.5 pr-3 pl-2 whitespace-nowrap">
            <span v-if="l.hoje" class="absolute inset-y-1 left-0 w-1 rounded-full bg-carimbo" aria-hidden="true" />
            <span class="carimbo font-semibold">{{ dataCurta(l.data) }}</span>
            <span class="ml-2 text-xs uppercase" :class="l.fds ? 'text-carimbo' : 'text-tinta-suave'">{{ diaSemanaCurto(l.data) }}</span>
            <span
              v-if="l.registro?.tipoDia === 'FERIADO' || (!l.registro && l.marcador?.tipo === 'feriado')"
              class="ml-1.5 rounded-[2px] bg-carimbo/10 px-1 text-[0.6rem] font-bold uppercase tracking-wide text-carimbo"
              :title="l.marcador?.rotulo"
            >feriado</span>
            <span
              v-else-if="l.registro?.tipoDia === 'AUSENCIA' || l.marcador?.tipo === 'ausencia'"
              class="ml-1.5 rounded-[2px] bg-credito/10 px-1 text-[0.6rem] font-bold uppercase tracking-wide text-credito"
              :title="l.marcador?.descricao ? `${l.marcador.rotulo} · ${l.marcador.descricao}` : 'Ausência: jornada base zero'"
            >{{ l.marcador?.rotulo ?? 'ausência' }}</span>
            <span
              v-if="l.registro?.registroManual"
              class="ml-1.5 rounded-[2px] bg-tinta/10 px-1 text-[0.6rem] font-bold uppercase tracking-wide text-tinta-suave"
              title="Lançamento manual (auditoria)"
            >manual</span>
            <span
              v-if="lancamentos[l.data]?.length"
              class="carimbo ml-1.5 rounded-[2px] px-1 text-[0.62rem] font-bold"
              :class="totalLancado(l.data) < 0 ? 'bg-carimbo/10 text-carimbo' : 'bg-credito/10 text-credito'"
              :title="`Lançado no banco de horas:\n${tituloLancamentos(l.data)}`"
            >banco {{ formatarSaldo(totalLancado(l.data), { curto: true }) }}</span>
          </td>

          <template v-if="l.registro">
            <td
              v-for="b in batidasDaLinha(l.registro)"
              :key="b.tipo"
              class="carimbo px-2 py-1.5 text-center"
              :class="classeBatida(l.registro, b)"
              :title="tituloBatida(b)"
            >
              <span class="relative" :class="b.ajustada ? 'border-b border-dashed border-current' : ''">{{ b.real ?? '--:--:--' }}<sup
                v-if="b.ajustada"
                class="absolute -top-1 left-full ml-0.5 text-[0.6rem] font-bold text-tinta-suave"
                aria-label="ajustada manualmente"
              >aj</sup></span>
            </td>
            <td class="carimbo px-2 py-1.5 text-right">{{ formatarDuracao(l.registro.segundosTrabalhados) }}</td>
            <td class="carimbo py-1.5 pr-2 pl-2 text-right font-semibold whitespace-nowrap" :class="classeSaldo(l.registro.saldoDiarioSegundos)">
              <template v-if="l.incompleto">
                <span class="text-xs font-semibold text-carimbo" title="Faltou batida neste dia: o saldo fica de fora até ajustar">incompleto</span>
              </template>
              <template v-else-if="l.registro.status === 'EM_ANDAMENTO'">
                <span class="text-xs font-normal italic text-tinta-suave">em andamento</span>
              </template>
              <template v-else>{{ formatarSaldo(l.registro.saldoDiarioSegundos) }}</template>
            </td>
          </template>

          <template v-else>
            <td :colspan="colunas.length + (lancamentos[l.data]?.length ? 1 : 2)" class="px-2 py-1.5 text-xs text-tinta-apagada">
              <span v-if="lancamentos[l.data]?.length" class="mr-3 text-tinta-suave">
                lançado no banco: {{ lancamentos[l.data].map((x) => x.descricao).join(' · ') }}
              </span>
              <span v-if="l.marcador" class="italic">{{ l.marcador.tipo === 'feriado' ? l.marcador.rotulo : 'jornada base zero' }}</span>
              <span v-else-if="podeLancar(l)" class="opacity-0 transition-opacity group-hover:opacity-100 group-focus:opacity-100">
                + lançar horas deste dia
              </span>
              <template v-else-if="!l.fds && !l.futuro">
                <span :class="podeMarcar() ? 'group-hover:hidden' : ''">sem registro</span>
                <span v-if="podeMarcar()" class="hidden group-hover:inline">+ folga, feriado ou justificativa</span>
              </template>
              <span v-else-if="!l.fds && podeMarcar()" class="opacity-0 transition-opacity group-hover:opacity-100">
                + folga, feriado ou justificativa
              </span>
              <span v-else>&nbsp;</span>
            </td>
            <td
              v-if="lancamentos[l.data]?.length"
              class="carimbo py-1.5 pr-2 pl-2 text-right font-semibold whitespace-nowrap"
              :class="classeSaldo(totalLancado(l.data))"
              :title="`Lançado no banco de horas:\n${tituloLancamentos(l.data)}`"
            >{{ formatarSaldo(totalLancado(l.data)) }}</td>
          </template>

          <td v-if="!somenteLeitura" class="py-1 pr-1 text-right whitespace-nowrap">
            <button
              type="button"
              class="rounded-[3px] p-1.5 text-tinta-suave opacity-0 transition hover:bg-tinta hover:text-cartao focus-visible:opacity-100 group-hover:opacity-100 group-focus:opacity-100"
              :aria-label="`Folga, feriado ou justificativa em ${dataCurta(l.data)}`"
              title="Folga, feriado ou justificativa"
              @click.stop="emit('marcar', l.data)"
              @keydown.enter.stop
            >
              <svg viewBox="0 0 16 16" class="size-3.5" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                <rect x="2.5" y="3.5" width="11" height="10" rx="1" /><path d="M2.5 6.5h11M5.5 2v3M10.5 2v3" stroke-linecap="round" />
              </svg>
            </button>
            <button
              v-if="podeAjustar(l)"
              type="button"
              class="rounded-[3px] p-1.5 transition hover:bg-tinta hover:text-cartao focus-visible:opacity-100"
              :class="l.incompleto ? 'text-carimbo opacity-100' : 'text-tinta-suave opacity-0 group-hover:opacity-100 group-focus:opacity-100'"
              :aria-label="`Ajustar batidas de ${dataCurta(l.data)}`"
              :title="l.incompleto ? 'Faltou batida: ajustar' : 'Ajustar batidas'"
              @click.stop="emit('ajustar', l.data)"
              @keydown.enter.stop
            >
              <svg viewBox="0 0 16 16" class="size-3.5" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                <path d="M10.5 2.5l3 3L6 13H3v-3l7.5-7.5z" stroke-linejoin="round" />
              </svg>
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
