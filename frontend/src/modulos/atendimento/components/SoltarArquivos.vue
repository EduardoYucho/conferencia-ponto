<script setup>
import { ref } from 'vue'

/** Área de arrastar e soltar (ou escolher) arquivos. Só entrega os arquivos: quem usa decide o que fazer. */
const props = defineProps({
  accept: { type: String, default: '' },
  multiple: { type: Boolean, default: false },
  desabilitado: { type: Boolean, default: false },
  compacto: { type: Boolean, default: false },
})
const emit = defineEmits(['arquivos'])

const campo = ref(null)
const arrastando = ref(false)

function entregar(lista) {
  const arquivos = [...(lista ?? [])]
  if (campo.value) campo.value.value = ''
  if (!arquivos.length || props.desabilitado) return
  emit('arquivos', props.multiple ? arquivos : arquivos.slice(0, 1))
}

function aoSoltar(evento) {
  arrastando.value = false
  entregar(evento.dataTransfer?.files)
}
</script>

<template>
  <div
    class="grid place-items-center rounded-[3px] border-2 border-dashed text-center transition"
    :class="[arrastando && !desabilitado ? 'border-tinta bg-papel-escuro/60' : 'border-linha', compacto ? 'px-4 py-4' : 'px-6 py-7',
             desabilitado ? 'opacity-60' : '']"
    @dragenter.prevent="arrastando = true"
    @dragover.prevent="arrastando = true"
    @dragleave.prevent="arrastando = false"
    @drop.prevent="aoSoltar"
  >
    <p class="text-sm">
      <slot>Arraste os arquivos para cá ou</slot>{{ ' ' }}<button type="button" class="font-semibold text-tinta underline underline-offset-4" :disabled="desabilitado" @click="campo?.click()">
        escolha {{ multiple ? 'os arquivos' : 'o arquivo' }}</button>.
    </p>
    <p v-if="$slots.dica" class="mt-1 text-xs text-tinta-apagada"><slot name="dica" /></p>
    <input ref="campo" type="file" :accept="accept" :multiple="multiple" class="sr-only" :disabled="desabilitado" @change="entregar($event.target.files)" />
  </div>
</template>
