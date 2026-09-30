<script setup>
import { computed } from 'vue'
import { formatarDuracao, formatarSaldo, minutosAgora, paraMinutos, paraSegundos } from '@/utils/tempo'

/**
 * Régua do dia: grade oficial (com janelas de tolerância) x batidas reais,
 * e os intervalos efetivamente apurados pelo motor.
 */
const props = defineProps({
  /** Registro do dia (RegistroJornadaResponse) */
  registro: { type: Object, required: true },
  /** { entrada1, saida1, entrada2, saida2 } no formato HH:mm */
  grade: { type: Object, default: null },
  toleranciaMinutos: { type: Number, default: 5 },
  hoje: { type: String, default: null },
  agora: { type: Date, default: () => new Date() },
})

const ehUtil = computed(() => props.registro.tipoDia === 'UTIL')
const ehHoje = computed(() => props.registro.data === props.hoje)
const agoraMin = computed(() => minutosAgora(props.agora))

const oficiais = computed(() => {
  if (!ehUtil.value || !props.grade) return []
  const g = props.grade
  return [g.entrada1, g.saida1, g.entrada2, g.saida2].map(paraMinutos)
})

/** Posição na régua em minutos (fração inclui os segundos). */
const emMinutos = (horario) => (horario ? paraSegundos(horario) / 60 : null)

/** Até 6 batidas: a 3ª entrada/saída só aparece quando existe. */
const batidas = computed(() =>
  props.registro.batidas
    .filter((b, i) => i < 4 || b.real)
    .map((b, i) => ({
    ...b,
    indice: i,
    entrada: i % 2 === 0,
    realMin: emMinutos(b.real),
    consMin: emMinutos(b.considerado),
    // tinta vermelha só faz sentido em dia útil (onde existe grade/tolerância)
    foraTolerancia: ehUtil.value && b.real !== null && !!b.oficial && !b.toleranciaAplicada,
  })),
)

/** Intervalos apurados (considerados). Intervalo aberto vai até "agora" se for hoje. */
const intervalos = computed(() => {
  const lista = []
  for (let i = 0; i < batidas.value.length; i += 2) {
    const e = batidas.value[i]
    const s = batidas.value[i + 1]
    if (!e || e.consMin === null) continue
    if (s && s.consMin !== null) {
      lista.push({ ini: e.consMin, fim: Math.max(e.consMin, s.consMin), aberto: false })
    } else {
      lista.push({ ini: e.consMin, fim: ehHoje.value ? Math.max(e.consMin, agoraMin.value) : e.consMin + 20, aberto: true })
    }
  }
  return lista
})

/** Janela visível: no mínimo 07h-19h, expandida para caber todas as marcações. */
const janela = computed(() => {
  const pontos = [
    ...oficiais.value,
    ...batidas.value.flatMap((b) => [b.realMin, b.consMin]),
    ...intervalos.value.map((i) => i.fim),
  ].filter((m) => m !== null)
  const inicio = Math.max(0, Math.floor(Math.min(7 * 60, ...pontos.map((m) => m - 20)) / 60) * 60)
  const fim = Math.min(24 * 60, Math.ceil(Math.max(19 * 60, ...pontos.map((m) => m + 20)) / 60) * 60)
  return { inicio, fim }
})

const pos = (min) => `${((min - janela.value.inicio) / (janela.value.fim - janela.value.inicio)) * 100}%`
const largura = (ini, fim) => `${((fim - ini) / (janela.value.fim - janela.value.inicio)) * 100}%`

const horas = computed(() => {
  const lista = []
  for (let m = janela.value.inicio; m <= janela.value.fim; m += 60) lista.push(m)
  return lista
})

const barrasOficiais = computed(() => {
  const o = oficiais.value
  return o.length ? [{ ini: o[0], fim: o[1] }, { ini: o[2], fim: o[3] }] : []
})

function descricaoDesvio(b) {
  if (b.desvioSegundos === null || b.desvioSegundos === undefined) return null
  if (b.desvioSegundos === 0) return 'no horário'
  return formatarSaldo(b.desvioSegundos)
}

const hm = (minutos) => formatarDuracao(Math.round(minutos * 60), { curto: true })
</script>

<template>
  <div>
    <!-- Régua -->
    <div class="grid grid-cols-[4.75rem_1fr] gap-x-3 sm:grid-cols-[5.5rem_1fr]">
      <div />
      <div class="relative h-5">
        <span
          v-for="h in horas"
          :key="h"
          class="carimbo absolute -translate-x-1/2 text-[0.68rem] text-tinta-apagada"
          :class="{ 'hidden sm:inline': (h / 60) % 2 === 1 }"
          :style="{ left: pos(h) }"
        >{{ String(h / 60).padStart(2, '0') }}h</span>
      </div>

      <!-- Faixa: grade oficial -->
      <div class="rotulo flex items-center">Oficial</div>
      <div class="relative h-9 border-y border-linha">
        <span
          v-for="h in horas"
          :key="`g${h}`"
          class="absolute inset-y-0 w-px bg-linha"
          :style="{ left: pos(h) }"
        />
        <template v-if="ehUtil">
          <div
            v-for="(b, i) in barrasOficiais"
            :key="`o${i}`"
            class="hachurado absolute inset-y-1.5 rounded-[2px] border border-tinta/60"
            :style="{ left: pos(b.ini), width: largura(b.ini, b.fim) }"
            :title="`Grade oficial ${hm(b.ini)} – ${hm(b.fim)}`"
          />
          <div
            v-for="(o, i) in oficiais"
            :key="`t${i}`"
            class="absolute inset-y-0 bg-carimbo/15"
            :style="{ left: pos(o - toleranciaMinutos), width: largura(0, toleranciaMinutos * 2) }"
            :title="`Tolerância ±${toleranciaMinutos} min em torno de ${hm(o)}`"
          />
        </template>
        <p v-else class="absolute inset-0 flex items-center pl-3 text-xs italic text-tinta-suave">
          Sem jornada prevista · 100% do tempo trabalhado é crédito
        </p>
      </div>

      <!-- Faixa: apurado + batidas reais -->
      <div class="rotulo flex items-center">Apurado</div>
      <div class="relative mb-8 mt-8 h-9 border-y border-linha">
        <span
          v-for="h in horas"
          :key="`a${h}`"
          class="absolute inset-y-0 w-px bg-linha"
          :style="{ left: pos(h) }"
        />
        <div
          v-for="(iv, i) in intervalos"
          :key="`i${i}`"
          class="absolute inset-y-1.5 rounded-[2px]"
          :class="iv.aberto ? (ehHoje ? 'em-andamento' : 'bg-gradient-to-r from-tinta to-transparent') : 'bg-tinta'"
          :style="{ left: pos(iv.ini), width: largura(iv.ini, iv.fim) }"
          :title="iv.aberto ? 'Intervalo em aberto (sem saída)' : `${formatarDuracao(Math.round((iv.fim - iv.ini) * 60))} entre as batidas consideradas`"
        />

        <!-- Batidas reais: tinta preta = absorvida pela tolerância; vermelha = minuto exato -->
        <template v-for="b in batidas" :key="b.tipo">
          <div
            v-if="b.realMin !== null"
            class="absolute -inset-y-1.5 w-0.5 -translate-x-1/2"
            :class="b.foraTolerancia ? 'bg-carimbo' : 'bg-tinta'"
            :style="{ left: pos(b.realMin) }"
          >
            <span
              class="carimbo absolute left-1/2 hidden -translate-x-1/2 whitespace-nowrap rounded-[2px] px-1 text-[0.7rem] font-medium sm:block"
              :class="[
                b.entrada ? '-top-6' : '-bottom-6',
                b.foraTolerancia ? 'bg-carimbo text-cartao' : 'text-tinta',
              ]"
            >{{ b.real }}</span>
          </div>
        </template>

        <!-- Agora -->
        <div
          v-if="ehHoje && agoraMin >= janela.inicio && agoraMin <= janela.fim"
          class="pointer-events-none absolute -inset-y-3 w-px bg-carimbo"
          :style="{ left: pos(agoraMin) }"
        >
          <span class="absolute -bottom-4 left-1 whitespace-nowrap text-[0.65rem] font-semibold uppercase tracking-wider text-carimbo">agora</span>
        </div>
      </div>
    </div>

    <!-- Detalhe por batida -->
    <dl
      class="grid grid-cols-2 gap-px overflow-hidden rounded-[3px] border border-linha bg-linha"
      :class="batidas.length > 4 ? 'sm:grid-cols-3 xl:grid-cols-6' : 'lg:grid-cols-4'"
    >
      <div v-for="b in batidas" :key="`d${b.tipo}`" class="bg-cartao px-3 py-3">
        <dt class="rotulo">{{ b.rotulo }}</dt>
        <dd class="mt-1">
          <span
            class="carimbo text-xl font-semibold"
            :class="b.real === null ? 'text-tinta-apagada' : b.foraTolerancia ? 'text-carimbo' : 'text-tinta'"
          >{{ b.real ?? '--:--:--' }}</span>
        </dd>
        <dd class="mt-1.5 space-y-0.5 text-xs text-tinta-suave">
          <p v-if="b.oficial">
            oficial <span class="carimbo text-tinta">{{ b.oficial }}</span>
            <template v-if="descricaoDesvio(b)"> · {{ descricaoDesvio(b) }}</template>
          </p>
          <p v-if="b.considerado">
            considerado <span class="carimbo font-semibold text-tinta">{{ b.considerado }}</span>
            <span
              v-if="ehUtil"
              class="ml-1 rounded-[2px] px-1 py-px text-[0.62rem] font-semibold uppercase tracking-wide"
              :class="b.toleranciaAplicada ? 'bg-tinta/10 text-tinta' : 'bg-carimbo/10 text-carimbo'"
            >{{ b.toleranciaAplicada ? 'tolerado' : 'fora da tolerância' }}</span>
          </p>
          <p v-if="b.ajustada" class="font-semibold text-tinta">
            <span class="rounded-[2px] bg-tinta/10 px-1 py-px text-[0.62rem] uppercase tracking-wide">ajustada manualmente</span>
          </p>
          <p v-if="b.real === null" class="italic">{{ registro.data >= hoje ? 'aguardando batida' : registro.status === 'EM_ANDAMENTO' ? 'sem batida (faltou)' : 'sem batida' }}</p>
        </dd>
      </div>
    </dl>
  </div>
</template>
