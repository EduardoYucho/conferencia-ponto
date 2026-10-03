<script setup>
import { ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { mensagemDe } from '@/utils/erros'

/**
 * Envio dos comprovantes PDF pela tela (arrastar e soltar ou escolher), para quem não tem uma pasta
 * monitorada ou quer mandar um comprovante na hora. Mesmas regras da pasta: o mesmo arquivo nunca gera
 * batida duas vezes.
 */
defineProps({
  compacto: { type: Boolean, default: false },
})
const emit = defineEmits(['enviados'])

const ROTULOS = {
  IMPORTADO: { texto: 'importado', classe: 'bg-credito/15 text-credito' },
  DUPLICADO: { texto: 'já existia', classe: 'bg-tinta/10 text-tinta-suave' },
  JA_PROCESSADO: { texto: 'já enviado', classe: 'bg-tinta/10 text-tinta-suave' },
  REJEITADO: { texto: 'recusado', classe: 'bg-carimbo/15 text-carimbo' },
  INVALIDO: { texto: 'inválido', classe: 'bg-carimbo/15 text-carimbo' },
  ENVIANDO: { texto: 'enviando…', classe: 'bg-amber-500/15 text-amber-700' },
  ERRO: { texto: 'não enviado', classe: 'bg-carimbo/15 text-carimbo' },
}
/** A API aceita até 50 por envio; manda em lotes para mostrar o progresso. */
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
      class="grid place-items-center rounded-[3px] border-2 border-dashed text-center transition"
      :class="[arrastando ? 'border-tinta bg-papel-escuro/60' : 'border-linha', compacto ? 'px-4 py-5' : 'px-6 py-8']"
      @dragenter.prevent="arrastando = true"
      @dragover.prevent="arrastando = true"
      @dragleave.prevent="arrastando = false"
      @drop.prevent="aoSoltar"
    >
      <svg viewBox="0 0 24 24" class="size-7 text-tinta-suave" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
        <path d="M12 16V4m0 0l-4 4m4-4l4 4M4 16v3a1 1 0 001 1h14a1 1 0 001-1v-3" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <p class="mt-2 text-sm">
        Arraste os comprovantes em PDF para cá ou
        <button type="button" class="font-semibold text-tinta underline underline-offset-4" :disabled="enviando" @click="escolher">escolha os arquivos</button>.
      </p>
      <p class="mt-1 text-xs text-tinta-apagada">O mesmo comprovante nunca gera batida duas vezes.</p>
      <input ref="campo" type="file" accept="application/pdf,.pdf" multiple class="sr-only" @change="enviar($event.target.files)" />
    </div>

    <p v-if="ignorados" role="alert" class="mt-2 text-sm text-carimbo">
      {{ ignorados }} arquivo(s) não entraram porque já havia um envio em andamento.
      {{ enviando ? 'Espere terminar e solte-os de novo.' : 'Solte-os de novo.' }}
    </p>

    <ul v-if="itens.length" class="mt-3 max-h-64 divide-y divide-linha/70 overflow-y-auto rounded-[3px] border border-linha text-sm" aria-live="polite">
      <li v-for="(item, i) in itens" :key="i" class="flex flex-wrap items-center gap-x-3 gap-y-0.5 px-3 py-2">
        <span class="rounded-[2px] px-1.5 py-0.5 text-[0.68rem] font-bold tracking-wider uppercase" :class="ROTULOS[item.status]?.classe">
          {{ ROTULOS[item.status]?.texto ?? item.status }}
        </span>
        <span class="min-w-0 flex-1 truncate font-semibold" :title="item.nome">{{ item.nome }}</span>
        <span v-if="item.mensagem" class="w-full text-xs text-tinta-suave">{{ item.mensagem }}</span>
      </li>
    </ul>
  </div>
</template>
