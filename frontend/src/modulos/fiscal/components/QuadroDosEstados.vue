<script setup>
import { computed } from 'vue'
import { NOMES_DOS_ESTADOS, ROTULOS_DOS_DOCUMENTOS, ruim, situacao } from '@/modulos/fiscal/formato'

/**
 * O quadro geral: uma linha por estado, uma coluna por documento e a contingência da NF-e. Cada célula mostra quem
 * autoriza (a sigla) na cor da situação; a linha abre o detalhe do estado. No celular o quadro rola na horizontal,
 * com a coluna do estado parada.
 */
const props = defineProps({
  estados: { type: Array, required: true },
  documentos: { type: Array, required: true },
  soProblemas: { type: Boolean, default: false },
  selecionado: { type: String, default: null },
})
const emit = defineEmits(['abrir'])

const celula = (estado, documento) => estado.celulas.find((c) => c.documento === documento)
const comProblema = (estado) =>
  estado.celulas.some((c) => ruim(c.situacao)) || (estado.contingencia && ruim(estado.contingencia.situacao))
const linhas = computed(() => (props.soProblemas ? props.estados.filter(comProblema) : props.estados))

function dica(c, rotulo) {
  if (!c) return `${rotulo}: sem autorizador configurado`
  return `${rotulo}: ${c.nome} (${c.autorizador}) — ${situacao(c.situacao).rotulo}${c.detalhe ? `. ${c.detalhe}` : ''}`
}
</script>

<template>
  <div class="cartao relative overflow-x-auto">
    <table class="w-full min-w-[40rem] border-collapse text-sm">
      <caption class="sr-only">Situação de cada documento fiscal por estado</caption>
      <thead>
        <tr class="border-b-2 border-tinta text-left">
          <th scope="col" class="sticky left-0 z-10 bg-cartao px-3 py-2"><span class="rotulo">Estado</span></th>
          <th v-for="d in documentos" :key="d.codigo" scope="col" class="px-2 py-2"><span class="rotulo">{{ d.rotulo }}</span></th>
          <th scope="col" class="px-2 py-2"><span class="rotulo">Contingência NF-e</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!linhas.length">
          <td :colspan="documentos.length + 2" class="px-3 py-6 text-center text-tinta-suave">Nenhum estado com problema agora.</td>
        </tr>
        <tr
          v-for="e in linhas"
          :key="e.uf"
          class="cursor-pointer border-b border-linha/70 transition last:border-b-0 hover:bg-papel-escuro/60"
          :class="selecionado === e.uf ? 'bg-papel-escuro' : ''"
          @click="emit('abrir', e.uf)"
        >
          <th scope="row" class="sticky left-0 z-10 bg-cartao px-3 py-1.5 text-left font-normal">
            <button
              type="button"
              class="flex items-baseline gap-2 text-left"
              :aria-label="`Abrir o detalhe de ${NOMES_DOS_ESTADOS[e.uf] ?? e.uf}`"
              @click.stop="emit('abrir', e.uf)"
            >
              <span class="carimbo font-bold">{{ e.uf }}</span>
              <span class="hidden truncate text-xs text-tinta-suave sm:inline">{{ NOMES_DOS_ESTADOS[e.uf] }}</span>
            </button>
          </th>
          <td v-for="d in documentos" :key="d.codigo" class="px-2 py-1.5">
            <span
              v-if="celula(e, d.codigo)"
              class="inline-flex min-w-[4.5rem] items-center justify-center rounded-[3px] px-2 py-1 font-mono text-xs font-semibold"
              :class="situacao(celula(e, d.codigo).situacao).classe"
              :title="dica(celula(e, d.codigo), d.rotulo)"
            >
              {{ celula(e, d.codigo).autorizador }}
              <span class="sr-only">— {{ situacao(celula(e, d.codigo).situacao).rotulo }}</span>
            </span>
            <span v-else class="text-tinta-apagada" :title="dica(null, ROTULOS_DOS_DOCUMENTOS[d.codigo])">—</span>
          </td>
          <td class="px-2 py-1.5">
            <span
              v-if="e.contingencia"
              class="inline-flex min-w-[4.5rem] items-center justify-center rounded-[3px] border-2 bg-cartao px-2 py-0.5 font-mono text-xs font-semibold"
              :class="[situacao(e.contingencia.situacao).borda, situacao(e.contingencia.situacao).texto]"
              :title="dica(e.contingencia, 'Contingência da NF-e')"
            >
              {{ e.contingencia.autorizador }}
              <span class="sr-only">— {{ situacao(e.contingencia.situacao).rotulo }}</span>
            </span>
            <span v-else class="text-tinta-apagada">—</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
