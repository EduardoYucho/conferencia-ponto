<script setup>
import { computed } from 'vue'
import { mensagemDe } from '@/utils/erros'
import Icone from '@/components/Icone.vue'

/**
 * Os três estados que toda lista ou painel tem antes de mostrar dados: carregando, falhou e vazio.
 * Sem isto a tela mostra zeros ou "nenhum registro" quando, na verdade, ainda não sabe.
 *
 * Uso:
 *   <EstadoDaTela :carregando="carregando" :erro="erro" :vazio="!linhas.length" vazio-texto="Sem registros neste mês." @tentar="carregar">
 *     ...conteúdo normal...
 *   </EstadoDaTela>
 *
 * Com `manter`, o conteúdo continua visível durante uma recarga ou falha (o aviso aparece acima dele).
 */
const props = defineProps({
  carregando: { type: Boolean, default: false },
  /** Erro capturado (ApiError, Error ou texto); vazio = sem erro. */
  erro: { type: [Object, String], default: null },
  vazio: { type: Boolean, default: false },
  vazioTexto: { type: String, default: 'Nada para mostrar.' },
  carregandoTexto: { type: String, default: 'Carregando…' },
  /** Já há conteúdo na tela: mantém visível e só acrescenta o aviso de falha. */
  manter: { type: Boolean, default: false },
  /** Sem o botão "Tentar de novo" (quando repetir não faz sentido). */
  semTentar: { type: Boolean, default: false },
})
defineEmits(['tentar'])

const mensagem = computed(() => {
  if (!props.erro) return ''
  return typeof props.erro === 'string' ? props.erro : mensagemDe(props.erro)
})
const mostrarConteudo = computed(() => props.manter || (!props.carregando && !props.erro && !props.vazio))
</script>

<template>
  <div
    v-if="mensagem"
    role="alert"
    class="aviso-erro flex flex-wrap items-center gap-x-3 gap-y-2"
    :class="{ 'mb-3': mostrarConteudo }"
  >
    <Icone nome="alerta" class="self-start" />
    <span class="min-w-0 flex-1 text-[0.95rem]">{{ mensagem }}</span>
    <button
      v-if="!semTentar"
      type="button"
      class="botao-secundario shrink-0 px-3! py-1.5!"
      :disabled="carregando"
      @click="$emit('tentar')"
    >{{ carregando ? 'Tentando…' : 'Tentar de novo' }}</button>
  </div>
  <p v-else-if="carregando && !manter" role="status" class="py-8 text-center text-[0.95rem] text-texto-3">
    {{ carregandoTexto }}
  </p>
  <p v-else-if="vazio && !carregando && !manter" class="py-8 text-center text-[0.95rem] text-texto-3">
    <slot name="vazio">{{ vazioTexto }}</slot>
  </p>
  <slot v-if="mostrarConteudo" />
</template>
