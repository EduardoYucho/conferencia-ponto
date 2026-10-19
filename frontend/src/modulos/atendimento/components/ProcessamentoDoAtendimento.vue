<script setup>
import { computed, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import { FALHAS_DA_CHAVE, SITUACOES, tokens, validadeDosLinks } from '@/modulos/atendimento/formato'
import SoltarArquivos from './SoltarArquivos.vue'

/**
 * A situação do processamento do atendimento (o download dos anexos, a análise de cada arquivo pelo Gemini e, no fim,
 * a redação do resumo), com a barra ao vivo e as ações: processar, cancelar, retomar e renovar os links com um PDF
 * novo do mesmo chamado.
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
})
const emit = defineEmits(['progresso', 'recarregar'])

const acao = ref('')
const mensagem = ref(null)
/** Sem resumo ainda: o "Processar" já pede o resumo no fim (a pessoa pode desmarcar). */
const gerarResumo = ref(true)

const situacao = computed(() => SITUACOES[props.atendimento.situacao] ?? SITUACOES.novo)
const arquivosApagados = computed(() => !!props.atendimento.arquivosApagadosEm)
const faltando = computed(() => props.atendimento.anexos.filter((a) => ['aguardando', 'falhou'].includes(a.situacao) && !a.vencido))
const todos = computed(() => [...props.atendimento.anexos, ...(props.atendimento.extras ?? [])])
/** Prontos sem análise pedida, ou cuja análise falhou por falta de chave (o "Processar" tenta de novo). */
const paraAnalisar = computed(() =>
  todos.value.filter((a) => a.analise === 'pendente' || (a.analise === 'falhou' && FALHAS_DA_CHAVE.includes(a.analiseCodigo))),
)
const rotuloDoBotao = computed(() => {
  const n = faltando.value.length
  const m = paraAnalisar.value.length
  if (n && m) return 'Baixar e analisar'
  if (n) return `Baixar ${n === 1 ? 'o anexo' : `os ${n} anexos`}`
  return `Analisar ${m === 1 ? 'o arquivo' : `os ${m} arquivos`}`
})
const vencidos = computed(() => props.atendimento.anexos.filter((a) => a.vencido || a.situacao === 'vencido'))
const emAndamento = computed(() => props.atendimento.situacao === 'processando')
const semResumo = computed(() => !(props.atendimento.saidas ?? []).some((s) => s.tipo === 'resumo'))
const escrevendo = computed(() => (props.atendimento.saidas ?? []).some((s) => ['pendente', 'redigindo'].includes(s.situacao)))
const pausado = computed(() => props.atendimento.situacao === 'pausado')

const explicacao = computed(() => {
  if (arquivosApagados.value) return 'Os arquivos deste atendimento já foram apagados pela retenção; as análises continuam aqui.'
  if (!todos.value.length) return 'A conversa não tem anexos, e nenhum arquivo foi enviado.'
  switch (props.atendimento.situacao) {
    case 'processando':
      return props.atendimento.percentual >= 100 && escrevendo.value
        ? 'Arquivos prontos. Escrevendo o resumo com o Gemini; a tela pode ser fechada.'
        : `Baixando e analisando os arquivos${escrevendo.value ? '; o resumo é escrito no fim' : ''}. A tela pode ser fechada: o processamento continua no servidor.`
    case 'pausado':
      return props.atendimento.motivoPausa ?? 'O processamento está pausado.'
    case 'pronto':
      return paraAnalisar.value.length
        ? 'Há arquivos que ainda não foram analisados.'
        : 'Todos os arquivos estão no servidor e foram analisados.'
    case 'com_falhas':
      return 'Alguns arquivos não foram baixados ou analisados: tente de novo, envie o arquivo à mão ou renove os links com um PDF novo.'
    case 'cancelado':
      return 'O processamento foi cancelado. O que falta pode ser processado de novo.'
    default:
      return 'Os anexos ainda não foram baixados nem analisados. Os links do Digisac valem 24 horas: baixe hoje.'
  }
})

async function executar(nome, chamada, sucesso) {
  acao.value = nome
  mensagem.value = null
  try {
    const progresso = await chamada()
    emit('progresso', progresso)
    if (sucesso) mensagem.value = { tipo: 'ok', texto: sucesso }
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

const processar = () => executar('processar', () =>
  atendimentoApi.processar(props.atendimento.id, semResumo.value && gerarResumo.value ? ['resumo'] : []))
const cancelar = () => executar('cancelar', () => atendimentoApi.cancelar(props.atendimento.id), 'Processamento cancelado.')
const retomar = () => executar('retomar', () => atendimentoApi.retomar(props.atendimento.id))

async function renovar([arquivo]) {
  acao.value = 'renovar'
  mensagem.value = null
  try {
    const resultado = await atendimentoApi.renovarPdf(props.atendimento.id, arquivo)
    mensagem.value = resultado.linksRenovados
      ? { tipo: 'ok', texto: `Links renovados de ${resultado.linksRenovados} anexo(s). Agora é só baixar.` }
      : { tipo: 'aviso', texto: 'Este PDF não trouxe link novo para os anexos que faltam (exporte a conversa de novo no Digisac).' }
    emit('recarregar')
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}
</script>

<template>
  <section class="cartao px-5 py-4" aria-label="Processamento">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-3">
        <h2 class="rotulo">Processamento</h2>
        <span class="rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacao.classe">{{ situacao.rotulo }}</span>
      </div>
      <div v-if="!arquivosApagados" class="flex flex-wrap items-center gap-3">
        <button v-if="emAndamento" type="button" class="botao-secundario" :disabled="!!acao" @click="cancelar">
          {{ acao === 'cancelar' ? 'Cancelando…' : 'Cancelar' }}
        </button>
        <button v-else-if="pausado" type="button" class="botao-primario" :disabled="!!acao" @click="retomar">
          {{ acao === 'retomar' ? 'Retomando…' : 'Retomar' }}
        </button>
        <template v-else-if="faltando.length || paraAnalisar.length">
          <label v-if="semResumo" class="flex items-center gap-2 text-sm">
            <input v-model="gerarResumo" type="checkbox" class="size-4 accent-tinta" />
            Gerar o resumo no fim
          </label>
          <button type="button" class="botao-primario" :disabled="!!acao" @click="processar">
            {{ acao === 'processar' ? 'Pondo na fila…' : rotuloDoBotao }}
          </button>
        </template>
      </div>
    </div>

    <p class="mt-2 text-sm" :class="pausado ? 'font-semibold text-carimbo' : 'text-tinta-suave'" :role="pausado ? 'alert' : undefined">
      {{ explicacao }}
    </p>
    <p v-if="pausado && /Gemini/.test(atendimento.motivoPausa ?? '')" class="mt-1 text-sm text-tinta-suave">
      Para não esperar: em Textos gerados, use <span class="font-semibold">Gerar pelo app do Gemini</span>, que não usa a cota da API.
    </p>
    <div v-if="atendimento.semChaveGemini" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm text-carimbo">
      Para os arquivos serem analisados, cadastre a sua chave do Gemini em
      <RouterLink :to="{ name: 'chave-gemini' }" class="font-semibold underline underline-offset-4">Minha chave do Gemini</RouterLink>
      e clique em Processar de novo.
    </div>
    <div v-if="todos.length && !arquivosApagados" class="mt-3">
      <div class="h-1.5 overflow-hidden rounded-full bg-linha" role="progressbar" :aria-valuenow="atendimento.percentual"
           aria-valuemin="0" aria-valuemax="100" aria-label="Downloads e análises concluídos">
        <div class="h-full transition-[width]" :class="emAndamento ? 'bg-tinta' : 'bg-tinta-suave'" :style="{ width: `${atendimento.percentual}%` }" />
      </div>
      <p class="mt-1 flex flex-wrap justify-between gap-x-4 text-xs text-tinta-suave">
        <span>{{ atendimento.percentual }}% concluído (downloads e análises)</span>
        <span v-if="atendimento.tokensEntrada || atendimento.tokensSaida">
          Gemini: {{ tokens(atendimento.tokensEntrada) }} tokens de entrada · {{ tokens(atendimento.tokensSaida) }} de saída
        </span>
      </p>
    </div>

    <p
      v-if="mensagem"
      :role="mensagem.tipo === 'erro' ? 'alert' : 'status'"
      class="mt-3 rounded-[3px] border px-3 py-2 text-sm"
      :class="mensagem.tipo === 'ok' ? 'border-credito/40 bg-credito/5 text-credito' : 'border-carimbo/40 bg-carimbo/5 text-carimbo'"
    >{{ mensagem.texto }}</p>

    <div v-if="vencidos.length && !arquivosApagados && !emAndamento" class="mt-4">
      <p class="text-sm">
        <span class="font-semibold text-carimbo">{{ vencidos.length }} anexo(s) com o link vencido</span><span
          v-if="atendimento.linksValidosAte"> ({{ validadeDosLinks(atendimento.linksValidosAte, true).toLowerCase() }})</span>.
        Exporte a conversa de novo no Digisac e solte o PDF aqui: os links que faltam são renovados.
      </p>
      <SoltarArquivos class="mt-2" accept="application/pdf,.pdf" compacto :desabilitado="!!acao" @arquivos="renovar">
        {{ acao === 'renovar' ? 'Lendo o PDF…' : 'Arraste o PDF novo do mesmo chamado ou' }}
      </SoltarArquivos>
    </div>
    <p v-else-if="atendimento.linksValidosAte && faltando.length" class="mt-2 text-xs text-tinta-suave">
      {{ validadeDosLinks(atendimento.linksValidosAte, atendimento.linksVencidos) }}
    </p>
  </section>
</template>
