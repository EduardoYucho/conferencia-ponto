<script setup>
import { ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { mensagemDe } from '@/utils/erros'
import Icone from '@/components/Icone.vue'

/**
 * Envio dos comprovantes em PDF pela tela (arrastar e soltar ou escolher os arquivos), para quem não tem uma
 * pasta de comprovantes ou quer mandar um comprovante na hora. O servidor lê cada PDF e diz o que aconteceu com
 * ele; o mesmo comprovante nunca conta duas vezes.
 */
defineProps({
  /** Versão menor, para usar dentro de uma seção (Minha conta). */
  compacto: { type: Boolean, default: false },
})
const emit = defineEmits(['enviados'])

/** O que aconteceu com cada arquivo (a situação vem do servidor; aqui só o nome e a cor da etiqueta). */
const SITUACOES = {
  IMPORTADO: { texto: 'importado', selo: 'selo-positivo' },
  DUPLICADO: { texto: 'já estava no sistema', selo: 'selo-neutro' },
  JA_PROCESSADO: { texto: 'já tinha sido enviado', selo: 'selo-neutro' },
  REJEITADO: { texto: 'não aceito', selo: 'selo-negativo' },
  INVALIDO: { texto: 'não aceito', selo: 'selo-negativo' },
  ENVIANDO: { texto: 'enviando…', selo: 'selo-atencao' },
  ERRO: { texto: 'não enviado', selo: 'selo-negativo' },
}
/** O servidor aceita vários por envio; mandar em grupos pequenos mostra o andamento. */
const LOTE = 10

const itens = ref([])
const arrastando = ref(false)
const enviando = ref(false)
const campo = ref(null)
/** Arquivos soltos durante um envio não entram: a pessoa precisa saber disso para soltar de novo. */
const ignorados = ref(0)

function escolher() {
  campo.value?.click()
}

async function enviar(lista) {
  const arquivos = [...(lista ?? [])]
  if (!arquivos.length) return
  if (enviando.value) {
    ignorados.value = arquivos.length
    return
  }
  ignorados.value = 0
  const pdfs = arquivos.filter((a) => a.name.toLowerCase().endsWith('.pdf'))
  for (const a of arquivos.filter((x) => !pdfs.includes(x))) {
    itens.value.unshift({ nome: a.name, status: 'INVALIDO', mensagem: 'Só arquivos PDF são aceitos.' })
  }
  if (!pdfs.length) return
  enviando.value = true
  const novos = pdfs.map((a) => ({ nome: a.name, status: 'ENVIANDO', mensagem: '' }))
  itens.value = [...novos, ...itens.value].slice(0, 200)
  let importados = 0
  try {
    for (let i = 0; i < pdfs.length; i += LOTE) {
      const lote = pdfs.slice(i, i + LOTE)
      try {
        const resultados = await pontoApi.enviarComprovantes(lote)
        resultados.forEach((r, j) => Object.assign(novos[i + j], { status: r.status, mensagem: r.mensagem }))
        importados += resultados.filter((r) => r.status === 'IMPORTADO').length
      } catch (e) {
        const motivo = mensagemDe(e)
        lote.forEach((_, j) => Object.assign(novos[i + j], { status: 'ERRO', mensagem: motivo }))
      }
      itens.value = [...itens.value]
    }
  } finally {
    enviando.value = false
    if (campo.value) campo.value.value = ''
  }
  emit('enviados', { total: pdfs.length, importados })
}

function aoSoltar(evento) {
  arrastando.value = false
  enviar(evento.dataTransfer?.files)
}

defineExpose({ enviar })
</script>

<template>
  <div>
    <div
      class="flex flex-col items-center rounded-2xl border-2 border-dashed text-center transition-colors"
      :class="[
        arrastando ? 'border-primaria bg-primaria-suave' : 'border-borda-forte bg-superficie-2',
        compacto ? 'gap-2 px-4 py-5' : 'gap-3 px-5 py-8',
      ]"
      @dragenter.prevent="arrastando = true"
      @dragover.prevent="arrastando = true"
      @dragleave.prevent="arrastando = false"
      @drop.prevent="aoSoltar"
    >
      <Icone nome="enviar" :tamanho="compacto ? 24 : 30" class="text-primaria" />
      <p class="font-bold" :class="compacto ? 'text-[0.95rem]' : 'text-base'">
        {{ arrastando ? 'Solte os comprovantes aqui' : 'Arraste os comprovantes em PDF para cá' }}
      </p>
      <button
        type="button"
        class="min-h-11"
        :class="compacto ? 'botao-secundario' : 'botao-primario'"
        :disabled="enviando"
        @click="escolher"
      >
        <Icone nome="documento" tamanho="18" /> {{ enviando ? 'Enviando…' : 'Escolher os arquivos' }}
      </button>
      <p class="text-sm text-texto-3">Pode enviar vários de uma vez. O mesmo comprovante nunca conta duas vezes.</p>
      <input
        ref="campo"
        type="file"
        accept="application/pdf,.pdf"
        multiple
        class="sr-only"
        tabindex="-1"
        aria-label="Escolher os comprovantes em PDF"
        @change="enviar($event.target.files)"
      />
    </div>

    <p v-if="ignorados" role="alert" class="aviso-atencao mt-3">
      {{ ignorados === 1 ? '1 arquivo não entrou' : `${ignorados} arquivos não entraram` }} porque já havia um envio em andamento.
      {{ enviando ? 'Espere terminar e solte de novo.' : 'Solte de novo.' }}
    </p>

    <template v-if="itens.length">
      <h3 class="rotulo mt-4">O que aconteceu com cada arquivo</h3>
      <ul class="mt-1.5 max-h-64 divide-y divide-borda overflow-y-auto rounded-xl border border-borda" aria-live="polite">
        <li v-for="(item, i) in itens" :key="i" class="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2.5">
          <span class="selo" :class="SITUACOES[item.status]?.selo ?? 'selo-neutro'">{{ SITUACOES[item.status]?.texto ?? item.status }}</span>
          <span class="min-w-0 flex-1 truncate text-[0.95rem] font-semibold" :title="item.nome">{{ item.nome }}</span>
          <span v-if="item.mensagem" class="w-full text-sm text-texto-2">{{ item.mensagem }}</span>
        </li>
      </ul>
    </template>
  </div>
</template>
