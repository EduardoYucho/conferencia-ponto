<script setup>
import { ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import { ACEITOS_NO_ENVIO, CATEGORIAS, dataHora, ORIGENS, tamanho } from '@/modulos/atendimento/formato'
import SoltarArquivos from './SoltarArquivos.vue'

/**
 * Ligações, o vídeo de reprodução e prints: a pessoa escolhe o que são e solta os arquivos. Cada um leva a data de
 * modificação, que entra na linha do tempo. O servidor confere o tipo pelos bytes.
 */
const props = defineProps({
  atendimentoId: { type: String, required: true },
  extras: { type: Array, required: true },
  bloqueado: { type: Boolean, default: false },
})
const emit = defineEmits(['recarregar'])

const origem = ref('ligacao')
/** { nome, progresso, erro } de cada arquivo do último envio */
const envios = ref([])
const enviando = ref(false)
const ocupado = ref('')
const falha = ref(null)

async function enviar(arquivos) {
  if (enviando.value) return
  enviando.value = true
  envios.value = arquivos.map((a) => ({ nome: a.name, progresso: 0, erro: null, ok: false }))
  let algum = false
  for (let i = 0; i < arquivos.length; i++) {
    const item = envios.value[i]
    try {
      await atendimentoApi.enviarExtra(props.atendimentoId, arquivos[i], origem.value, (p) => (item.progresso = p))
      item.ok = true
      algum = true
    } catch (e) {
      item.erro = mensagemDe(e)
    }
    envios.value = [...envios.value]
  }
  enviando.value = false
  envios.value = envios.value.filter((e) => e.erro)
  if (algum) emit('recarregar')
}

async function tirar(arquivo) {
  ocupado.value = arquivo.id
  falha.value = null
  try {
    await atendimentoApi.tirarArquivo(props.atendimentoId, arquivo.id)
    emit('recarregar')
  } catch (e) {
    falha.value = { id: arquivo.id, texto: mensagemDe(e) }
  } finally {
    ocupado.value = ''
  }
}
</script>

<template>
  <section class="cartao px-5 py-4" aria-label="Ligações, vídeo e prints">
    <h2 class="rotulo">Ligações, vídeo e prints</h2>
    <p class="mt-1 text-sm text-tinta-suave">
      O que não está na conversa: a gravação da ligação, o vídeo de reprodução do problema e prints. A data de
      modificação de cada arquivo entra na linha do tempo.
    </p>

    <div v-if="!bloqueado" class="mt-3 grid grid-cols-[minmax(0,1fr)] gap-3">
      <fieldset class="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        <legend class="sr-only">O que são os arquivos</legend>
        <label v-for="(rotulo, codigo) in ORIGENS" :key="codigo" class="flex items-center gap-1.5">
          <input v-model="origem" type="radio" name="origem" :value="codigo" class="accent-tinta" :disabled="enviando" />
          {{ rotulo }}
        </label>
      </fieldset>
      <SoltarArquivos multiple :accept="ACEITOS_NO_ENVIO" :desabilitado="enviando" compacto @arquivos="enviar">
        {{ enviando ? 'Enviando…' : `Arraste ${ORIGENS[origem] === 'Print' ? 'os prints' : 'os arquivos'} para cá ou` }}
        <template #dica>Imagem, áudio, vídeo, PDF ou texto. Word e Excel: exporte em PDF.</template>
      </SoltarArquivos>
      <ul v-if="envios.length" class="grid grid-cols-[minmax(0,1fr)] gap-1 text-xs break-words" aria-live="polite">
        <li v-for="(e, i) in envios" :key="i" :class="e.erro ? 'text-carimbo' : 'text-tinta-suave'">
          <span class="font-semibold">{{ e.nome }}:</span>
          {{ e.erro ?? (e.ok ? 'enviado' : `enviando… ${Math.round((e.progresso ?? 0) * 100)}%`) }}
        </li>
      </ul>
    </div>

    <ul v-if="extras.length" class="mt-3 divide-y divide-linha/70 rounded-[3px] border border-linha text-sm">
      <li v-for="x in extras" :key="x.id" class="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
        <span class="rounded-[2px] bg-tinta/10 px-1.5 py-0.5 text-[0.68rem] font-bold tracking-wider uppercase">
          {{ ORIGENS[x.origem] ?? x.origem }} {{ x.ordem }}
        </span>
        <span class="min-w-0 flex-1 truncate font-semibold" :title="x.nome">{{ x.nome }}</span>
        <span class="flex w-full flex-wrap items-center gap-x-3 gap-y-1 text-xs text-tinta-suave sm:w-auto">
          <span>{{ CATEGORIAS[x.categoria] ?? x.categoria }} · {{ tamanho(x.tamanho) }}</span>
          <span>{{ x.momento ? dataHora(x.momento) : 'sem data' }}</span>
          <button v-if="!bloqueado" type="button" class="underline underline-offset-4" :disabled="!!ocupado" @click="tirar(x)">
            {{ ocupado === x.id ? 'Tirando…' : 'Tirar' }}
          </button>
        </span>
        <p v-if="falha?.id === x.id" role="alert" class="w-full text-xs font-semibold text-carimbo">{{ falha.texto }}</p>
      </li>
    </ul>
    <p v-else-if="bloqueado" class="mt-3 text-sm text-tinta-suave">Nenhum arquivo enviado.</p>
  </section>
</template>
