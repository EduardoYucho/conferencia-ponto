<script setup>
import { estadosEmTexto, porcentagem, tempo } from '@/modulos/fiscal/formato'

/** A porcentagem no ar de cada autorizador no período (o pior primeiro, por documento). */
defineProps({
  linhas: { type: Array, required: true },
})

const cor = (p) => (p === null || p === undefined ? 'bg-linha' : p >= 99 ? 'bg-credito' : p >= 95 ? 'bg-alerta' : 'bg-carimbo')
</script>

<template>
  <div class="cartao relative overflow-x-auto">
    <table class="w-full min-w-[40rem] border-collapse text-sm">
      <caption class="sr-only">Disponibilidade de cada autorizador no período</caption>
      <thead>
        <tr class="border-b-2 border-tinta text-left">
          <th scope="col" class="px-3 py-2"><span class="rotulo">Documento</span></th>
          <th scope="col" class="px-3 py-2"><span class="rotulo">Autorizador</span></th>
          <th scope="col" class="px-3 py-2"><span class="rotulo">Estados</span></th>
          <th scope="col" class="w-48 px-3 py-2"><span class="rotulo">No ar</span></th>
          <th scope="col" class="px-3 py-2 text-right"><span class="rotulo">Leituras</span></th>
          <th scope="col" class="px-3 py-2 text-right whitespace-nowrap"><span class="rotulo">Tempo médio</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="l in linhas" :key="`${l.documento}:${l.autorizador}`" class="border-b border-linha/70 last:border-b-0">
          <td class="px-3 py-2 font-semibold whitespace-nowrap">{{ l.rotuloDocumento }}</td>
          <td class="px-3 py-2">{{ l.nome }} <span class="carimbo text-xs text-tinta-suave">({{ l.autorizador }})</span></td>
          <td class="px-3 py-2 text-tinta-suave">{{ estadosEmTexto(l.estados) }}</td>
          <td class="px-3 py-2">
            <div class="flex items-center gap-2">
              <div class="h-2 flex-1 overflow-hidden rounded-full bg-papel-escuro" aria-hidden="true">
                <div class="h-full" :class="cor(l.percentual)" :style="{ width: `${l.percentual ?? 0}%` }" />
              </div>
              <span class="carimbo w-14 text-right font-semibold">{{ porcentagem(l.percentual) }}</span>
            </div>
          </td>
          <td class="carimbo px-3 py-2 text-right whitespace-nowrap" :title="`${l.ruins} ruins, ${l.semInformacao} sem informação`">
            {{ l.leituras }}<span v-if="l.ruins" class="text-carimbo"> · {{ l.ruins }} ruins</span>
          </td>
          <td class="carimbo px-3 py-2 text-right whitespace-nowrap">{{ tempo(l.tempoMedioMs) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
