<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import { dataHora, tokens } from '@/modulos/atendimento/formato'

/**
 * A análise de um arquivo pelo Gemini, aberta na tela: a tela, os campos e as mensagens de um print; a transcrição
 * de um áudio (com quem fala e o instante); o conteúdo e os dados de um documento; os passos de um vídeo, com o
 * instante, a tela, as ações, as mensagens e o quadro de cada um (tirado pelo ffmpeg do servidor). Já vem mascarada
 * do servidor.
 */
const props = defineProps({
  atendimentoId: { type: String, required: true },
  arquivoId: { type: String, required: true },
})

const analise = ref(null)
const carregando = ref(true)
const erro = ref('')
const copiado = ref(false)

const r = computed(() => analise.value?.resultado ?? {})
const naoIdentificado = (v) => !v || String(v).trim().toLowerCase() === 'não identificado'
const TIPOS_DE_MENSAGEM = { erro: 'Erro', aviso: 'Aviso', informacao: 'Informação', confirmacao: 'Confirmação', outro: 'Mensagem' }
const PAPEIS = { cliente: 'cliente', atendente: 'atendente', outro: 'outra pessoa', nao_identificado: '' }

const papelDe = computed(() => Object.fromEntries((r.value.falantes ?? []).map((f) => [f.rotulo, PAPEIS[f.papel] ?? ''])))
const transcricao = computed(() =>
  (r.value.trechos ?? r.value.narracao ?? []).map((t) => `[${t.inicio}] ${t.falante}: ${t.texto}`).join('\n'),
)

/** O quadro de cada passo do vídeo (pelo número do passo) e as miniaturas já baixadas (por instante). */
const quadroDoPasso = computed(() => Object.fromEntries((analise.value?.quadros ?? []).map((q) => [q.passo, q])))
const miniaturas = ref({})
const semMiniatura = ref({})

async function carregarQuadros() {
  const instantes = [...new Set((analise.value?.quadros ?? []).filter((q) => q.disponivel).map((q) => q.instanteMs))]
  for (const ms of instantes) {
    try {
      const resposta = await atendimentoApi.quadro(props.atendimentoId, props.arquivoId, ms)
      miniaturas.value = { ...miniaturas.value, [ms]: URL.createObjectURL(resposta.data) }
    } catch {
      semMiniatura.value = { ...semMiniatura.value, [ms]: true }
    }
  }
}

onUnmounted(() => Object.values(miniaturas.value).forEach((url) => URL.revokeObjectURL(url)))

onMounted(async () => {
  try {
    analise.value = await atendimentoApi.analise(props.atendimentoId, props.arquivoId)
    if (analise.value?.categoria === 'video') carregarQuadros()
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    carregando.value = false
  }
})

async function copiarTranscricao() {
  try {
    await navigator.clipboard.writeText(transcricao.value)
    copiado.value = true
    setTimeout(() => (copiado.value = false), 2500)
  } catch {
    erro.value = 'Não foi possível copiar: selecione o texto e copie com Ctrl+C.'
  }
}
</script>

<template>
  <div class="rounded-[3px] border border-linha bg-papel-escuro/30 px-4 py-3 text-sm" aria-label="Análise do arquivo">
    <p v-if="carregando" role="status" class="text-tinta-suave">Abrindo a análise…</p>
    <p v-else-if="erro" role="alert" class="text-carimbo">{{ erro }}</p>
    <template v-else-if="analise">
      <!-- imagem -->
      <template v-if="analise.categoria === 'imagem'">
        <p>{{ r.descricao }}</p>
        <dl v-if="r.tela" class="mt-2 grid gap-x-4 gap-y-1 text-xs sm:grid-cols-3">
          <div v-if="!naoIdentificado(r.tela.nome)"><dt class="rotulo">Tela</dt><dd class="mt-0.5 text-sm">{{ r.tela.nome }}</dd></div>
          <div v-if="!naoIdentificado(r.tela.codigo)"><dt class="rotulo">Código</dt><dd class="carimbo mt-0.5 text-sm">{{ r.tela.codigo }}</dd></div>
          <div v-if="!naoIdentificado(r.tela.caminhoMenu)"><dt class="rotulo">Menu</dt><dd class="mt-0.5 text-sm">{{ r.tela.caminhoMenu }}</dd></div>
        </dl>
        <ul v-if="r.mensagens?.length" class="mt-2 grid gap-1">
          <li v-for="(m, i) in r.mensagens" :key="i" class="rounded-[2px] border-l-2 px-2 py-1"
              :class="m.tipo === 'erro' ? 'border-carimbo bg-carimbo/5' : 'border-tinta/30 bg-cartao'">
            <span class="rotulo mr-2">{{ TIPOS_DE_MENSAGEM[m.tipo] ?? 'Mensagem' }}</span>
            <span class="break-words whitespace-pre-wrap">{{ m.texto }}</span>
          </li>
        </ul>
        <table v-if="r.campos?.length" class="mt-2 w-full text-xs">
          <tbody>
            <tr v-for="(c, i) in r.campos" :key="i" class="border-b border-linha/60 align-top">
              <th class="py-1 pr-3 text-left font-semibold">{{ c.rotulo }}</th>
              <td class="py-1 break-words">{{ c.valor }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="r.textoLegivel === false" class="mt-2 text-xs text-carimbo">O texto da imagem não estava legível.</p>
      </template>

      <!-- áudio -->
      <template v-else-if="analise.categoria === 'audio'">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <p class="text-xs text-tinta-suave">
            Transcrição<span v-if="!naoIdentificado(r.duracao)"> · {{ r.duracao }}</span>
            <span v-if="r.temTrechoInaudivel" class="text-carimbo"> · com trechos inaudíveis</span>
          </p>
          <button v-if="r.trechos?.length" type="button" class="text-xs font-semibold underline underline-offset-4" @click="copiarTranscricao">
            {{ copiado ? 'Copiada' : 'Copiar a transcrição' }}
          </button>
        </div>
        <ol class="mt-2 grid gap-1.5">
          <li v-for="(t, i) in r.trechos ?? []" :key="i" class="grid grid-cols-[3.2rem_minmax(0,1fr)] gap-2">
            <span class="carimbo text-xs text-tinta-suave">{{ t.inicio }}</span>
            <p class="break-words">
              <span class="font-semibold">{{ t.falante }}</span>
              <span v-if="papelDe[t.falante]" class="text-xs text-tinta-suave"> ({{ papelDe[t.falante] }})</span>:
              {{ t.texto }}
            </p>
          </li>
        </ol>
        <p v-if="!r.trechos?.length" class="text-tinta-suave">Nenhuma fala foi identificada no áudio.</p>
      </template>

      <!-- vídeo -->
      <template v-else-if="analise.categoria === 'video'">
        <p>{{ r.descricao }}</p>
        <p class="mt-1 text-xs text-tinta-suave">
          Vídeo<span v-if="!naoIdentificado(r.duracao)"> · {{ r.duracao }}</span>
          · {{ (r.passos ?? []).length }} {{ (r.passos ?? []).length === 1 ? 'passo' : 'passos' }}
          <span v-if="r.textoLegivel === false" class="text-carimbo"> · o texto da tela não estava legível</span>
        </p>
        <p v-if="analise.avisoDosQuadros" role="note" class="mt-2 rounded-[2px] border border-carimbo/40 bg-carimbo/5 px-2 py-1.5 text-xs text-carimbo">
          {{ analise.avisoDosQuadros }}
        </p>
        <ol class="mt-3 grid gap-3" aria-label="Passos do vídeo">
          <li v-for="(p, i) in r.passos ?? []" :key="i" class="grid gap-3 rounded-[3px] border border-linha bg-cartao p-2 sm:grid-cols-[9rem_minmax(0,1fr)]">
            <div>
              <template v-if="quadroDoPasso[i + 1]">
                <a v-if="miniaturas[quadroDoPasso[i + 1].instanteMs]" :href="miniaturas[quadroDoPasso[i + 1].instanteMs]" target="_blank" rel="noopener"
                   :title="`Abrir o quadro de ${quadroDoPasso[i + 1].instante}`">
                  <img :src="miniaturas[quadroDoPasso[i + 1].instanteMs]" :alt="`Quadro do passo ${i + 1} (${quadroDoPasso[i + 1].instante})`"
                       class="aspect-video w-full rounded-[2px] border border-linha bg-white object-contain" />
                </a>
                <div v-else class="grid aspect-video w-full place-items-center rounded-[2px] border border-dashed border-linha px-1 text-center text-[0.7rem] text-tinta-apagada">
                  {{ quadroDoPasso[i + 1].disponivel && !semMiniatura[quadroDoPasso[i + 1].instanteMs] ? 'abrindo…' : 'sem quadro' }}
                </div>
                <p class="carimbo mt-1 text-center text-[0.7rem] text-tinta-suave">quadro {{ quadroDoPasso[i + 1].instante }}</p>
              </template>
              <div v-else class="grid aspect-video w-full place-items-center rounded-[2px] border border-dashed border-linha text-[0.7rem] text-tinta-apagada">sem instante</div>
            </div>
            <div class="min-w-0">
              <p class="flex flex-wrap items-baseline gap-x-2">
                <span class="rotulo">Passo {{ i + 1 }}</span>
                <span v-if="!naoIdentificado(p.instante)" class="carimbo text-xs">{{ p.instante }}</span>
              </p>
              <p class="mt-0.5 break-words">{{ naoIdentificado(p.descricao) ? 'Sem descrição' : p.descricao }}</p>
              <p v-if="p.tela && (!naoIdentificado(p.tela.nome) || !naoIdentificado(p.tela.codigo))" class="mt-1 text-xs text-tinta-suave">
                Tela <span v-if="!naoIdentificado(p.tela.nome)" class="font-semibold text-tinta">{{ p.tela.nome }}</span>
                <span v-if="!naoIdentificado(p.tela.codigo)" class="carimbo"> ({{ p.tela.codigo }})</span>
                <span v-if="!naoIdentificado(p.tela.caminhoMenu)"> · {{ p.tela.caminhoMenu }}</span>
              </p>
              <ul v-if="p.acoes?.length" class="mt-1 list-disc pl-5 text-xs">
                <li v-for="(a, k) in p.acoes" :key="k" class="break-words">{{ a }}</li>
              </ul>
              <ul v-if="p.mensagens?.length" class="mt-1 grid gap-1">
                <li v-for="(m, k) in p.mensagens" :key="k" class="rounded-[2px] border-l-2 px-2 py-1 text-xs"
                    :class="m.tipo === 'erro' ? 'border-carimbo bg-carimbo/5' : 'border-tinta/30 bg-papel-escuro/30'">
                  <span class="rotulo mr-2">{{ TIPOS_DE_MENSAGEM[m.tipo] ?? 'Mensagem' }}</span>
                  <span class="break-words whitespace-pre-wrap">{{ m.texto }}</span>
                </li>
              </ul>
            </div>
          </li>
        </ol>
        <p v-if="!r.passos?.length" class="mt-2 text-tinta-suave">Nenhum passo foi identificado no vídeo.</p>
        <details v-if="r.narracao?.length" class="mt-3">
          <summary class="cursor-pointer text-xs font-semibold">Narração ({{ r.narracao.length }} {{ r.narracao.length === 1 ? 'fala' : 'falas' }})</summary>
          <div class="mt-1 flex justify-end">
            <button type="button" class="text-xs font-semibold underline underline-offset-4" @click="copiarTranscricao">
              {{ copiado ? 'Copiada' : 'Copiar a narração' }}
            </button>
          </div>
          <ol class="mt-1 grid gap-1.5">
            <li v-for="(t, k) in r.narracao" :key="k" class="grid grid-cols-[3.2rem_minmax(0,1fr)] gap-2">
              <span class="carimbo text-xs text-tinta-suave">{{ t.inicio }}</span>
              <p class="break-words"><span class="font-semibold">{{ t.falante }}</span>: {{ t.texto }}</p>
            </li>
          </ol>
        </details>
      </template>

      <!-- documento -->
      <template v-else>
        <p><span v-if="!naoIdentificado(r.tipoDocumento)" class="rotulo mr-2">{{ r.tipoDocumento }}</span>{{ r.descricao }}</p>
        <ul v-if="r.mensagens?.length" class="mt-2 grid gap-1">
          <li v-for="(m, i) in r.mensagens" :key="i" class="rounded-[2px] border-l-2 border-carimbo bg-carimbo/5 px-2 py-1 break-words">{{ m }}</li>
        </ul>
        <table v-if="r.dados?.length" class="mt-2 w-full text-xs">
          <tbody>
            <tr v-for="(d, i) in r.dados" :key="i" class="border-b border-linha/60 align-top">
              <th class="py-1 pr-3 text-left font-semibold">{{ d.rotulo }}</th>
              <td class="py-1 break-words">{{ d.valor }}</td>
            </tr>
          </tbody>
        </table>
        <details v-if="!naoIdentificado(r.conteudo)" class="mt-2">
          <summary class="cursor-pointer text-xs font-semibold">Conteúdo lido</summary>
          <p class="mt-1 max-h-72 overflow-auto rounded-[2px] bg-cartao px-2 py-1 text-xs break-words whitespace-pre-wrap">{{ r.conteudo }}</p>
        </details>
      </template>

      <p class="mt-3 text-[0.7rem] text-tinta-apagada">
        {{ analise.modelo }} · prompt v{{ analise.versaoPrompt }} · {{ dataHora(analise.criadaEm) }}
        <template v-if="analise.tokensEntrada != null"> · {{ tokens(analise.tokensEntrada) }} + {{ tokens(analise.tokensSaida) }} tokens</template>
        <template v-else> · reaproveitada de um arquivo igual</template>
        <span v-if="!analise.atual" class="text-carimbo"> · de outra versão (processe de novo para atualizar)</span>
      </p>
    </template>
  </div>
</template>
