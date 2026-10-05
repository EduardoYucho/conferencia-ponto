<script setup>
import { computed } from 'vue'
import { formatarSaldo, nomeMes } from '@/utils/tempo'

/**
 * Barras de saldo mensal ao redor do zero (verde = crédito, vermelho = débito).
 * Serve para o ano (12 meses) e para o ciclo do banco de horas (os meses do ciclo).
 */
const props = defineProps({
  /** [{ ano, mes, saldoMensalSegundos, saldoAnualAcumuladoSegundos }] */
  meses: { type: Array, default: () => [] },
  /** { ano, mes } em destaque (os demais ficam esmaecidos) */
  destaque: { type: Object, default: null },
  rotuloAcumulado: { type: String, default: 'acumulado' },
})

const maximo = computed(() => Math.max(3600, ...props.meses.map((m) => Math.abs(m.saldoMensalSegundos))))
const altura = (seg) => `${(Math.abs(seg) / maximo.value) * 100}%`
const ehDestaque = (m) => props.destaque && m.ano === props.destaque.ano && m.mes === props.destaque.mes
</script>

<template>
  <div
    class="grid h-20 items-stretch gap-1"
    :style="{ gridTemplateColumns: `repeat(${Math.max(meses.length, 1)}, minmax(0, 1fr))` }"
    role="img"
    aria-label="Saldo mês a mês"
  >
    <div
      v-for="m in meses"
      :key="`${m.ano}-${m.mes}`"
      class="flex flex-col"
      :title="`${nomeMes(m.mes)}/${m.ano}: ${formatarSaldo(m.saldoMensalSegundos)} (${rotuloAcumulado} ${formatarSaldo(m.saldoAnualAcumuladoSegundos)})`"
    >
      <div class="flex flex-1 items-end border-b border-tinta/70">
        <div
          v-if="m.saldoMensalSegundos > 0"
          class="w-full rounded-t-[1px] bg-credito"
          :class="{ 'opacity-45': !ehDestaque(m) }"
          :style="{ height: altura(m.saldoMensalSegundos) }"
        />
      </div>
      <div class="flex flex-1 items-start">
        <div
          v-if="m.saldoMensalSegundos < 0"
          class="w-full rounded-b-[1px] bg-carimbo"
          :class="{ 'opacity-45': !ehDestaque(m) }"
          :style="{ height: altura(m.saldoMensalSegundos) }"
        />
      </div>
      <span
        class="mt-0.5 text-center text-[0.6rem] font-semibold uppercase"
        :class="ehDestaque(m) ? 'text-tinta' : 'text-tinta-apagada'"
      >{{ meses.length > 12 ? nomeMes(m.mes).charAt(0) : nomeMes(m.mes).slice(0, 3) }}</span>
    </div>
  </div>
</template>
