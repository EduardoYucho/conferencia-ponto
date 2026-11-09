<script setup>
import { computed } from 'vue'
import { situacao } from '@/modulos/fiscal/formato'

/** A situação num selo colorido (com o texto: a cor nunca é a única pista). */
const props = defineProps({
  codigo: { type: String, default: 'SEM_INFORMACAO' },
  curto: { type: Boolean, default: false },
})
const s = computed(() => situacao(props.codigo))
</script>

<template>
  <span class="inline-flex shrink-0 items-center rounded-[3px] px-1.5 py-0.5 text-[0.7rem] leading-none font-bold tracking-wide whitespace-nowrap uppercase" :class="s.classe">
    {{ curto ? s.curto : s.rotulo }}
  </span>
</template>
