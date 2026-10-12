<script setup>
import { computed } from 'vue'
import { LIMITES_DOS_CAMPOS, problemasDosCampos } from '@/modulos/atendimento/formato'

/**
 * Os campos que a pessoa informa para os chamados do desenvolvimento (não estão na conversa nem nos arquivos). Saem
 * no cabeçalho e no rodapé, montados pelo servidor na hora: mudar um campo não exige gerar o texto de novo. A
 * versão do cliente é a data da última atualização do sistema no cliente (um dado só, que sai nos dois lugares).
 */
const props = defineProps({
  modelValue: { type: Object, required: true },
  /** Para os ids dos campos (a tela pode ter mais de um formulário). */
  prefixo: { type: String, default: 'campos' },
  desligado: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const problemas = computed(() => problemasDosCampos(props.modelValue))

function mudar(campo, valor) {
  emit('update:modelValue', { ...props.modelValue, [campo]: valor })
}

const OPCOES = [
  { valor: true, rotulo: 'Sim' },
  { valor: false, rotulo: 'Não' },
  { valor: null, rotulo: 'Não informado' },
]
</script>

<template>
  <div class="grid gap-3 text-sm sm:grid-cols-2">
    <div>
      <label :for="`${prefixo}-versao`" class="rotulo">Versão do cliente</label>
      <input
        :id="`${prefixo}-versao`"
        type="text"
        class="campo mt-1 font-sans! text-sm!"
        :value="modelValue.versaoCliente"
        :disabled="desligado"
        placeholder="ex.: 15/09/2026"
        :aria-describedby="`${prefixo}-versao-dica`"
        @input="mudar('versaoCliente', $event.target.value)"
      />
      <p :id="`${prefixo}-versao-dica`" class="mt-1 text-xs text-tinta-suave">
        A data da última atualização do sistema no cliente (no chamado de erro, sai também no rodapé).
      </p>
    </div>
    <fieldset>
      <legend class="rotulo">Testado na última versão disponível</legend>
      <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        <label v-for="opcao in OPCOES" :key="String(opcao.valor)" class="flex items-center gap-1.5">
          <input
            type="radio"
            class="size-4 accent-tinta"
            :name="`${prefixo}-testado`"
            :checked="modelValue.testadoUltimaVersao === opcao.valor"
            :disabled="desligado"
            @change="mudar('testadoUltimaVersao', opcao.valor)"
          />
          {{ opcao.rotulo }}
        </label>
      </div>
    </fieldset>
    <div class="sm:col-span-2">
      <label :for="`${prefixo}-backup`" class="rotulo">Caminho do backup</label>
      <input
        :id="`${prefixo}-backup`"
        type="text"
        class="campo mt-1 font-sans! text-sm!"
        :value="modelValue.caminhoBackup"
        :disabled="desligado"
        @input="mudar('caminhoBackup', $event.target.value)"
      />
    </div>
    <div class="sm:col-span-2">
      <label :for="`${prefixo}-chamado`" class="rotulo">Chamado relacionado <span class="font-normal normal-case">(opcional)</span></label>
      <input
        :id="`${prefixo}-chamado`"
        type="text"
        class="campo mt-1 font-sans! text-sm!"
        :value="modelValue.chamadoRelacionado"
        :disabled="desligado"
        placeholder="link ou número de um chamado anterior"
        @input="mudar('chamadoRelacionado', $event.target.value)"
      />
    </div>
    <p class="text-xs text-tinta-suave sm:col-span-2">
      Campo vazio sai como "não informado". Limites: versão {{ LIMITES_DOS_CAMPOS.versaoCliente }}, backup
      {{ LIMITES_DOS_CAMPOS.caminhoBackup }} e chamado relacionado {{ LIMITES_DOS_CAMPOS.chamadoRelacionado }} caracteres.
    </p>
    <ul v-if="problemas.length" role="alert" class="text-sm font-semibold text-carimbo sm:col-span-2">
      <li v-for="p in problemas" :key="p">{{ p }}</li>
    </ul>
  </div>
</template>
