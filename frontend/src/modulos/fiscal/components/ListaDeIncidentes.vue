<script setup>
import SeloDaSituacao from '@/modulos/fiscal/components/SeloDaSituacao.vue'
import { dataHora, duracao, estadosEmTexto, hora } from '@/modulos/fiscal/formato'

/**
 * Os incidentes do período, o mais recente primeiro: quando começou e terminou, quanto durou, o documento, quem
 * autoriza, os estados afetados, a pior situação e o motivo (quando a fonte informou). É aqui que se responde
 * "às 10h20 a SEFAZ-SP estava fora".
 */
defineProps({
  incidentes: { type: Array, required: true },
  agora: { type: Date, required: true },
})
</script>

<template>
  <ol class="cartao divide-y divide-linha/70">
    <li v-for="i in incidentes" :key="i.id" class="px-4 py-3 sm:px-5">
      <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
        <SeloDaSituacao :codigo="i.pior" curto />
        <span class="font-bold">{{ i.rotuloDocumento }}</span>
        <span>· {{ i.nome }} <span class="carimbo text-tinta-suave">({{ i.autorizador }})</span></span>
        <span v-if="i.aberto" class="rounded-[3px] border border-carimbo px-1.5 py-0.5 text-[0.7rem] font-bold text-carimbo uppercase">Em andamento</span>
      </div>
      <p class="mt-1 text-sm">
        <span class="carimbo">{{ dataHora(i.inicio) }}</span>
        <template v-if="i.fim"> → <span class="carimbo">{{ hora(i.fim, new Date(i.inicio)) }}</span></template>
        · {{ duracao(i.duracaoMinutos) }}
        <span class="text-tinta-suave">· {{ i.leituras }} {{ i.leituras === 1 ? 'leitura ruim' : 'leituras ruins' }}</span>
      </p>
      <p class="mt-0.5 text-sm text-tinta-suave">Estados: {{ estadosEmTexto(i.estados, 10) }}</p>
      <p class="mt-0.5 text-sm" :class="i.detalhe ? '' : 'text-tinta-suave italic'">{{ i.detalhe || 'Motivo não informado.' }}</p>
    </li>
  </ol>
</template>
