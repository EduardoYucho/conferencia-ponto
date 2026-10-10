<script setup>
import { computed, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import { SITUACOES, validadeDosLinks } from '@/modulos/atendimento/formato'
import SoltarArquivos from './SoltarArquivos.vue'

/**
 * A situação do processamento do atendimento (nesta versão, o download dos anexos), com a barra ao vivo e as ações:
 * baixar os anexos, cancelar, retomar e renovar os links com um PDF novo do mesmo chamado.
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
})
const emit = defineEmits(['progresso', 'recarregar'])

const acao = ref('')
const mensagem = ref(null)

const situacao = computed(() => SITUACOES[props.atendimento.situacao] ?? SITUACOES.novo)
const arquivosApagados = computed(() => !!props.atendimento.arquivosApagadosEm)
const faltando = computed(() => props.atendimento.anexos.filter((a) => ['aguardando', 'falhou'].includes(a.situacao) && !a.vencido))
const vencidos = computed(() => props.atendimento.anexos.filter((a) => a.vencido || a.situacao === 'vencido'))
const emAndamento = computed(() => props.atendimento.situacao === 'processando')
const pausado = computed(() => props.atendimento.situacao === 'pausado')

const explicacao = computed(() => {
  if (arquivosApagados.value) return 'Os arquivos deste atendimento já foram apagados pela retenção.'
  if (!props.atendimento.anexos.length) return 'A conversa não tem anexos para baixar.'
  switch (props.atendimento.situacao) {
    case 'processando':
      return 'Baixando os anexos. A tela pode ser fechada: o processamento continua no servidor.'
    case 'pausado':
      return props.atendimento.motivoPausa ?? 'O processamento está pausado.'
    case 'pronto':
      return 'Todos os anexos estão no servidor.'
    case 'com_falhas':
      return 'Alguns anexos não foram baixados: tente de novo, envie o arquivo à mão ou renove os links com um PDF novo.'
    case 'cancelado':
      return 'O processamento foi cancelado. Os anexos que faltam podem ser baixados de novo.'
    default:
      return 'Os anexos ainda não foram baixados. Os links do Digisac valem 24 horas: baixe hoje.'
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

const processar = () => executar('processar', () => atendimentoApi.processar(props.atendimento.id))
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
      <div v-if="!arquivosApagados" class="flex flex-wrap gap-2">
        <button v-if="emAndamento" type="button" class="botao-secundario" :disabled="!!acao" @click="cancelar">
          {{ acao === 'cancelar' ? 'Cancelando…' : 'Cancelar' }}
        </button>
        <button v-else-if="pausado" type="button" class="botao-primario" :disabled="!!acao" @click="retomar">
          {{ acao === 'retomar' ? 'Retomando…' : 'Retomar' }}
        </button>
        <button v-else-if="faltando.length" type="button" class="botao-primario" :disabled="!!acao" @click="processar">
          {{ acao === 'processar' ? 'Pondo na fila…' : `Baixar ${faltando.length === 1 ? 'o anexo' : `os ${faltando.length} anexos`}` }}
        </button>
      </div>
    </div>

    <p class="mt-2 text-sm" :class="pausado ? 'font-semibold text-carimbo' : 'text-tinta-suave'" :role="pausado ? 'alert' : undefined">
      {{ explicacao }}
    </p>
    <div v-if="atendimento.anexos.length && !arquivosApagados" class="mt-3">
      <div class="h-1.5 overflow-hidden rounded-full bg-linha" role="progressbar" :aria-valuenow="atendimento.percentual"
           aria-valuemin="0" aria-valuemax="100" aria-label="Anexos resolvidos">
        <div class="h-full transition-[width]" :class="emAndamento ? 'bg-tinta' : 'bg-tinta-suave'" :style="{ width: `${atendimento.percentual}%` }" />
      </div>
      <p class="mt-1 text-xs text-tinta-suave">{{ atendimento.percentual }}% dos anexos resolvidos</p>
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
