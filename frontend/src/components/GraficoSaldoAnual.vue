<script setup>
import { computed } from 'vue'
import { saldo, saldoComSentido } from '@/utils/horas'
import { nomeMes } from '@/utils/tempo'

/**
 * Saldo de cada mês em barras a partir de uma linha do zero: para cima (verde) é a favor, para baixo (vermelho)
 * é devendo. Serve para o ano (12 meses) e para o período do banco de horas (os meses do período).
 * Só desenha: os saldos vêm prontos do servidor. O valor de cada mês aparece ao passar o mouse ou ao focar o
 * mês (teclado e toque) e está escrito para leitores de tela.
 */
const props = defineProps({
  /** [{ ano, mes, saldoMensalSegundos, saldoAnualAcumuladoSegundos }] */
  meses: { type: Array, default: () => [] },
  /** { ano, mes } em destaque (o mês atual) */
  destaque: { type: Object, default: null },
  /** Como chamar o acumulado na dica: "no ano", "no período". */
  rotuloAcumulado: { type: String, default: 'acumulado' },
})

/** Barra mais alta de cada lado (com um piso de 1h para um saldo pequeno não virar uma barra enorme). */
const maiorAFavor = computed(() => Math.max(0, ...props.meses.map((m) => m.saldoMensalSegundos)))
const maiorDevendo = computed(() => Math.max(0, ...props.meses.map((m) => -m.saldoMensalSegundos)))
const escala = computed(() => Math.max(3600, maiorAFavor.value + maiorDevendo.value))
/** Altura de cada lado da linha do zero, em % da área do desenho (o lado sem valores não ocupa espaço). */
const ladoAFavor = computed(() => (maiorAFavor.value / escala.value) * 100)
const ladoDevendo = computed(() => (maiorDevendo.value / escala.value) * 100)
const alturaDaBarra = (segundos, maior) => `${maior ? (Math.abs(segundos) / maior) * 100 : 0}%`

const ehDestaque = (m) => !!props.destaque && m.ano === props.destaque.ano && m.mes === props.destaque.mes
/** Com poucos meses cabe o valor escrito em cada barra (em telas largas); com muitos, só na dica. */
const cabeValor = computed(() => props.meses.length <= 7)
const abreviado = (m) => (props.meses.length > 12 ? nomeMes(m.mes).charAt(0) : nomeMes(m.mes).slice(0, 3))
/** "Junho de 2026: + 10h 11min a favor (no período: + 10h 11min a favor)" */
const descricao = (m) => `${nomeMes(m.mes)} de ${m.ano}: ${saldoComSentido(m.saldoMensalSegundos)}`
  + ` (${props.rotuloAcumulado}: ${saldoComSentido(m.saldoAnualAcumuladoSegundos)})`
/** A dica não pode sair da tela: nos primeiros meses abre para a direita, nos últimos para a esquerda. */
function ladoDaDica(indice) {
  const terco = props.meses.length / 3
  if (indice < terco) return 'left-0'
  if (indice >= props.meses.length - terco) return 'right-0'
  return 'left-1/2 -translate-x-1/2'
}
</script>

<template>
  <ul
    class="grid"
    :style="{ gridTemplateColumns: `repeat(${Math.max(meses.length, 1)}, minmax(0, 1fr))` }"
    aria-label="Saldo de cada mês"
  >
    <li
      v-for="(m, i) in meses"
      :key="`${m.ano}-${m.mes}`"
      class="group relative flex flex-col rounded-lg pt-1"
      :class="ehDestaque(m) ? 'bg-neutro' : 'hover:bg-superficie-2 focus:bg-superficie-2'"
      tabindex="0"
    >
      <span class="sr-only">{{ descricao(m) }}{{ ehDestaque(m) ? ' — mês atual' : '' }}</span>

      <!-- o desenho: barra para cima (a favor) ou para baixo (devendo), a partir da linha do zero -->
      <div class="flex h-28 flex-col justify-end pt-6" :class="{ 'pb-6': maiorDevendo > 0 }" aria-hidden="true">
        <div class="flex flex-col items-center justify-end" :style="{ height: `${ladoAFavor}%` }">
          <template v-if="m.saldoMensalSegundos > 0">
            <span v-if="cabeValor" class="mb-0.5 hidden text-[0.8rem] font-semibold whitespace-nowrap text-texto-2 md:block">{{ saldo(m.saldoMensalSegundos) }}</span>
            <div class="w-full max-w-6 shrink-0 rounded-t-[4px] bg-positivo-solido" :style="{ height: alturaDaBarra(m.saldoMensalSegundos, maiorAFavor), minHeight: '3px' }" />
          </template>
        </div>
        <div class="h-px shrink-0 bg-borda-forte" />
        <div class="flex flex-col items-center justify-start" :style="{ height: `${ladoDevendo}%` }">
          <template v-if="m.saldoMensalSegundos < 0">
            <div class="w-full max-w-6 shrink-0 rounded-b-[4px] bg-negativo-solido" :style="{ height: alturaDaBarra(m.saldoMensalSegundos, maiorDevendo), minHeight: '3px' }" />
            <span v-if="cabeValor" class="mt-0.5 hidden text-[0.8rem] font-semibold whitespace-nowrap text-texto-2 md:block">{{ saldo(m.saldoMensalSegundos) }}</span>
          </template>
        </div>
      </div>

      <span
        class="pb-1 text-center text-[0.8rem] leading-tight"
        :class="ehDestaque(m) ? 'font-bold text-texto' : 'font-semibold text-texto-3'"
        aria-hidden="true"
      >{{ abreviado(m) }}</span>

      <!-- dica com o valor do mês (mouse, teclado e toque) -->
      <span
        class="pointer-events-none absolute bottom-full z-10 mb-1 hidden w-max max-w-56 rounded-lg bg-inverso px-2.5 py-1.5 text-left text-sm text-sobre-inverso shadow-lg group-hover:block group-focus:block"
        :class="ladoDaDica(i)"
        aria-hidden="true"
      >
        <b class="block">{{ nomeMes(m.mes) }} de {{ m.ano }}<template v-if="ehDestaque(m)"> · mês atual</template></b>
        <span class="block">{{ saldoComSentido(m.saldoMensalSegundos) }}</span>
        <span class="block">{{ rotuloAcumulado }}: {{ saldoComSentido(m.saldoAnualAcumuladoSegundos) }}</span>
      </span>
    </li>
  </ul>
</template>
