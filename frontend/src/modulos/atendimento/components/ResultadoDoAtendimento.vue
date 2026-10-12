<script setup>
import { computed, ref, watch } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import { SITUACOES_DA_SAIDA, TIPOS_DE_SAIDA } from '@/modulos/atendimento/formato'
import SaidaDoAtendimento from './SaidaDoAtendimento.vue'

/**
 * Os textos gerados do atendimento, uma aba por tipo (nesta versão, o resumo). Enquanto o texto é escrito, a aba
 * mostra a situação ao vivo; pronto, abre o texto com o assunto, a prévia, as imagens e a cópia.
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
})
const emit = defineEmits(['progresso'])

const aba = ref(null)
const acao = ref('')
const mensagem = ref('')

const saidas = computed(() => props.atendimento.saidas ?? [])
const atual = computed(() => saidas.value.find((s) => s.tipo === aba.value) ?? saidas.value[0] ?? null)
const processando = computed(() => ['processando', 'pausado'].includes(props.atendimento.situacao))
/** Anexos que ainda não foram baixados: o "Gerar o resumo" baixa e analisa antes. */
const faltaBaixar = computed(() => (props.atendimento.anexos ?? []).some((a) => a.situacao === 'aguardando' && !a.vencido))

watch(saidas, (lista) => {
  if (!lista.some((s) => s.tipo === aba.value)) aba.value = lista[0]?.tipo ?? null
}, { immediate: true })

function rotulo(tipo) {
  return TIPOS_DE_SAIDA.find((t) => t.codigo === tipo)?.rotulo ?? tipo
}

const espera = computed(() => {
  const s = atual.value
  if (!s) return ''
  if (props.atendimento.situacao === 'pausado') return props.atendimento.motivoPausa ?? 'O processamento está pausado.'
  if (props.atendimento.situacao === 'cancelado') return 'O processamento foi cancelado. Clique em Processar para continuar.'
  if (s.situacao === 'redigindo') return 'Escrevendo o texto com o Gemini…'
  return s.erro ?? 'O texto é escrito quando os downloads e as análises terminarem.'
})

async function gerarResumo() {
  acao.value = 'gerar'
  mensagem.value = ''
  try {
    emit('progresso', await atendimentoApi.processar(props.atendimento.id, ['resumo']))
  } catch (e) {
    mensagem.value = mensagemDe(e)
  } finally {
    acao.value = ''
  }
}

async function gerarDeNovo(tipo) {
  acao.value = 'gerar'
  mensagem.value = ''
  try {
    emit('progresso', await atendimentoApi.gerarDeNovo(props.atendimento.id, tipo))
  } catch (e) {
    mensagem.value = mensagemDe(e)
  } finally {
    acao.value = ''
  }
}
</script>

<template>
  <section class="cartao px-5 py-4" aria-label="Textos gerados">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="rotulo">Textos gerados</h2>
      <div v-if="saidas.length > 1" role="tablist" class="flex flex-wrap gap-1">
        <button
          v-for="s in saidas"
          :key="s.tipo"
          type="button"
          role="tab"
          :aria-selected="s.tipo === atual?.tipo"
          class="rounded-[2px] border px-2 py-1 text-xs font-semibold"
          :class="s.tipo === atual?.tipo ? 'border-tinta bg-tinta text-cartao' : 'border-linha'"
          @click="aba = s.tipo"
        >{{ rotulo(s.tipo) }}</button>
      </div>
    </div>

    <div v-if="!saidas.length" class="mt-2 flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-tinta-suave">
        Nenhum texto pedido ainda. O resumo é escrito pelo Gemini a partir da conversa e da análise de cada arquivo<span
          v-if="faltaBaixar">; os anexos que faltam são baixados e analisados antes</span>.
      </p>
      <button v-if="!processando" type="button" class="botao-primario" :disabled="!!acao" @click="gerarResumo">
        {{ acao ? 'Pedindo…' : 'Gerar o resumo' }}
      </button>
    </div>

    <template v-else-if="atual">
      <p class="mt-1 flex flex-wrap items-center gap-x-2 text-sm">
        <span class="font-semibold">{{ rotulo(atual.tipo) }}</span>
        <span class="text-xs text-tinta-suave">versão {{ atual.versao }}</span>
        <span class="text-xs" :class="SITUACOES_DA_SAIDA[atual.situacao]?.classe">{{ SITUACOES_DA_SAIDA[atual.situacao]?.rotulo ?? atual.situacao }}</span>
      </p>

      <div v-if="atual.situacao === 'pendente' || atual.situacao === 'redigindo'" class="mt-3" role="status">
        <p class="text-sm" :class="atendimento.situacao === 'pausado' ? 'font-semibold text-carimbo' : 'text-tinta-suave'">{{ espera }}</p>
        <div v-if="atendimento.situacao === 'processando'" class="mt-2 h-1.5 overflow-hidden rounded-full bg-linha" aria-hidden="true">
          <div class="h-full w-1/3 animate-pulse bg-tinta" />
        </div>
      </div>

      <div v-else-if="atual.situacao === 'falhou'" class="mt-3 flex flex-wrap items-center justify-between gap-3">
        <p role="alert" class="text-sm text-carimbo">{{ atual.erro ?? 'Não foi possível escrever o texto.' }}</p>
        <button type="button" class="botao-primario" :disabled="!!acao || processando" @click="gerarDeNovo(atual.tipo)">
          {{ acao ? 'Pedindo…' : 'Gerar de novo' }}
        </button>
      </div>

      <SaidaDoAtendimento
        v-else
        :key="atual.id"
        class="mt-3"
        :atendimento-id="atendimento.id"
        :saida-id="atual.id"
        @progresso="emit('progresso', $event)"
      />
    </template>

    <p v-if="mensagem" role="alert" class="mt-3 text-sm text-carimbo">{{ mensagem }}</p>
  </section>
</template>
