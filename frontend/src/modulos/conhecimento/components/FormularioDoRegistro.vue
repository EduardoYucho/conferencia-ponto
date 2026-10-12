<script setup>
import { computed } from 'vue'
import { LIMITES_DO_REGISTRO, problemasDoRegistro, SITUACOES_DO_REGISTRO, TIPOS_DO_REGISTRO } from '@/modulos/conhecimento/formato'

/**
 * Os campos de um registro da base: a identificação (título, tipo, cliente, módulo, tela), o problema e a resolução.
 * É o que entra na pesquisa. Na confirmação de um texto, também a situação e o chamado no desenvolvimento (depois,
 * a situação muda na tela do registro, com histórico).
 */
const props = defineProps({
  modelValue: { type: Object, required: true },
  /** Para os ids dos campos (a tela pode ter mais de um formulário). */
  prefixo: { type: String, default: 'registro' },
  desligado: { type: Boolean, default: false },
  comSituacao: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const problemas = computed(() => problemasDoRegistro(props.modelValue))
const id = (campo) => `${props.prefixo}-${campo}`

function mudar(campo, valor) {
  emit('update:modelValue', { ...props.modelValue, [campo]: valor })
}

function tamanho(campo) {
  return String(props.modelValue[campo] ?? '').trim().length
}

const longo = (campo) => tamanho(campo) > LIMITES_DO_REGISTRO[campo]

const CURTOS = [
  { campo: 'cliente', rotulo: 'Cliente' },
  { campo: 'contato', rotulo: 'Contato' },
  { campo: 'modulo', rotulo: 'Módulo', dica: 'ex.: Vendas > Pedidos' },
  { campo: 'telaNome', rotulo: 'Tela' },
  { campo: 'telaCodigo', rotulo: 'Código da tela', dica: 'ex.: VEN010' },
]

const TEXTOS = [
  { campo: 'sintoma', rotulo: 'Sintoma', dica: 'O que o cliente viu ou relatou.' },
  { campo: 'mensagemErro', rotulo: 'Mensagem de erro', dica: 'Como aparece na tela, sem mudar nada (a pesquisa acha mesmo com uma palavra diferente).', literal: true },
  { campo: 'causa', rotulo: 'Causa' },
  { campo: 'resolucao', rotulo: 'Resolução', dica: 'O que resolveu (ou o que foi pedido ao desenvolvimento).' },
]
</script>

<template>
  <div class="grid gap-3 text-sm">
    <div>
      <label :for="id('titulo')" class="rotulo">Título</label>
      <input
        :id="id('titulo')"
        type="text"
        class="campo mt-1 font-sans! text-sm!"
        :class="longo('titulo') || !String(modelValue.titulo ?? '').trim() ? 'border-carimbo!' : ''"
        :value="modelValue.titulo"
        :disabled="desligado"
        @input="mudar('titulo', $event.target.value)"
      />
      <p class="mt-1 text-xs" :class="longo('titulo') ? 'font-semibold text-carimbo' : 'text-tinta-suave'">
        {{ tamanho('titulo') }}/{{ LIMITES_DO_REGISTRO.titulo }} caracteres
      </p>
    </div>

    <fieldset>
      <legend class="rotulo">Tipo</legend>
      <div class="mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
        <label v-for="tipo in TIPOS_DO_REGISTRO" :key="tipo.codigo" class="flex items-center gap-1.5">
          <input
            type="radio"
            class="size-4 accent-tinta"
            :name="id('tipo')"
            :checked="modelValue.tipo === tipo.codigo"
            :disabled="desligado"
            @change="mudar('tipo', tipo.codigo)"
          />
          {{ tipo.rotulo }}
        </label>
      </div>
    </fieldset>

    <div class="grid gap-3 sm:grid-cols-2">
      <div v-for="c in CURTOS" :key="c.campo" :class="c.campo === 'modulo' ? 'sm:col-span-2' : ''">
        <label :for="id(c.campo)" class="rotulo">{{ c.rotulo }}</label>
        <input
          :id="id(c.campo)"
          type="text"
          class="campo mt-1 font-sans! text-sm!"
          :class="longo(c.campo) ? 'border-carimbo!' : ''"
          :value="modelValue[c.campo]"
          :placeholder="c.dica ?? ''"
          :disabled="desligado"
          @input="mudar(c.campo, $event.target.value)"
        />
      </div>
    </div>

    <div v-for="t in TEXTOS" :key="t.campo">
      <label :for="id(t.campo)" class="rotulo">{{ t.rotulo }}</label>
      <textarea
        :id="id(t.campo)"
        rows="3"
        class="campo mt-1 text-sm!"
        :class="[t.literal ? 'carimbo' : 'font-sans!', longo(t.campo) ? 'border-carimbo!' : '']"
        :value="modelValue[t.campo]"
        :disabled="desligado"
        @input="mudar(t.campo, $event.target.value)"
      />
      <p v-if="t.dica" class="mt-0.5 text-xs text-tinta-suave">{{ t.dica }}</p>
    </div>

    <div v-if="comSituacao" class="grid gap-3 sm:grid-cols-2">
      <div>
        <label :for="id('situacao')" class="rotulo">Situação</label>
        <select :id="id('situacao')" class="campo mt-1 font-sans! text-sm!" :value="modelValue.situacao" :disabled="desligado" @change="mudar('situacao', $event.target.value)">
          <option v-for="s in SITUACOES_DO_REGISTRO" :key="s.codigo" :value="s.codigo">{{ s.rotulo }}</option>
        </select>
      </div>
      <div v-if="modelValue.situacao !== 'resolvido_suporte'">
        <label :for="id('chamadoDev')" class="rotulo">Chamado no desenvolvimento <span class="font-normal normal-case">(se já existe)</span></label>
        <input
          :id="id('chamadoDev')"
          type="text"
          class="campo mt-1 font-sans! text-sm!"
          :class="longo('chamadoDev') ? 'border-carimbo!' : ''"
          :value="modelValue.chamadoDev"
          :disabled="desligado"
          placeholder="ex.: número do OTRS"
          @input="mudar('chamadoDev', $event.target.value)"
        />
      </div>
    </div>

    <ul v-if="problemas.length" role="alert" class="list-disc pl-5 text-xs font-semibold text-carimbo">
      <li v-for="p in problemas" :key="p">{{ p }}</li>
    </ul>
  </div>
</template>
