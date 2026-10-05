<script setup>
import { computed } from 'vue'
import { formatarDuracao } from '@/utils/tempo'

const props = defineProps({
  /** Segundos já trabalhados (inclui o intervalo em aberto, calculado ao vivo). */
  segundos: { type: Number, default: 0 },
  /** Jornada base em segundos (08:48:00). */
  jornadaBase: { type: Number, default: 31680 },
  emAndamento: { type: Boolean, default: false },
  semJornada: { type: Boolean, default: false },
  /** O dia de hoje ainda não é conhecido (carregando ou falhou): mostra "—" em vez de zeros e de uma jornada suposta. */
  semDados: { type: Boolean, default: false },
})

// A escala cresce se houver hora extra, mantendo a meta visível.
const escala = computed(() => Math.max(props.jornadaBase * 1.12, props.segundos * 1.04, 3600))
const pct = (min) => `${Math.min(100, (min / escala.value) * 100)}%`

const normal = computed(() => Math.min(props.segundos, props.jornadaBase))
const extra = computed(() => Math.max(0, props.segundos - props.jornadaBase))
const faltam = computed(() => Math.max(0, props.jornadaBase - props.segundos))
const percentual = computed(() =>
  props.jornadaBase ? Math.round((props.segundos / props.jornadaBase) * 100) : 0,
)
const marcasHora = computed(() => {
  const marcas = []
  for (let m = 3600; m < escala.value; m += 3600) marcas.push(m)
  return marcas
})
</script>

<template>
  <div>
    <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
      <p v-if="semDados" class="carimbo text-3xl font-semibold leading-none text-tinta-apagada sm:text-4xl">—</p>
      <p v-else class="carimbo text-3xl font-semibold leading-none whitespace-nowrap sm:text-4xl">
        {{ formatarDuracao(segundos) }}
        <span v-if="!semJornada" class="text-base font-normal text-tinta-apagada">/ {{ formatarDuracao(jornadaBase) }}</span>
      </p>
      <p v-if="!semDados" class="text-sm text-tinta-suave">
        <template v-if="semJornada">sem jornada prevista</template>
        <template v-else-if="extra > 0"><span class="carimbo font-semibold text-credito">+{{ formatarDuracao(extra) }}</span> além da jornada</template>
        <template v-else>faltam <span class="carimbo font-semibold text-tinta">{{ formatarDuracao(faltam) }}</span></template>
      </p>
    </div>

    <div
      class="relative mt-4 h-5 overflow-hidden rounded-[2px] border border-tinta/80 bg-papel"
      role="progressbar"
      :aria-valuenow="semDados ? undefined : percentual"
      aria-valuemin="0"
      :aria-valuemax="100"
      :aria-label="semDados ? 'Progresso da jornada: ainda sem dados' : `Progresso da jornada: ${percentual}%`"
    >
      <div
        v-if="!semDados"
        class="absolute inset-y-0 left-0 transition-[width] duration-700"
        :class="emAndamento && extra === 0 ? 'em-andamento' : 'bg-tinta'"
        :style="{ width: pct(normal) }"
      />
      <div
        v-if="extra > 0 && !semDados"
        class="absolute inset-y-0 transition-[width] duration-700"
        :class="emAndamento ? 'em-andamento listra-credito' : 'bg-credito'"
        :style="{ left: pct(jornadaBase), width: pct(extra) }"
      />
      <span
        v-for="m in marcasHora"
        :key="m"
        class="absolute inset-y-0 w-px bg-cartao/40 mix-blend-difference"
        :style="{ left: pct(m) }"
      />
    </div>

    <div class="relative mt-1 h-5 text-[0.7rem]">
      <span
        v-if="!semJornada && !semDados"
        class="carimbo absolute -translate-x-1/2 text-carimbo"
        :style="{ left: pct(jornadaBase) }"
      >▲ {{ formatarDuracao(jornadaBase, { curto: true }) }}</span>
      <span class="absolute left-0 text-tinta-apagada">0h</span>
    </div>
  </div>
</template>
