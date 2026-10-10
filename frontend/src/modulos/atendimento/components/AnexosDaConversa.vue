<script setup>
import { ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import { ACEITOS_NO_ENVIO, CATEGORIAS, hora, SITUACOES_DO_ANEXO, tamanho } from '@/modulos/atendimento/formato'

/**
 * Os anexos da conversa, cada um com a situação do download e, quando não deu para baixar, o motivo e as saídas:
 * tentar de novo, enviar o arquivo à mão (o "plano B") ou tirar.
 */
const props = defineProps({
  atendimentoId: { type: String, required: true },
  anexos: { type: Array, required: true },
  bloqueado: { type: Boolean, default: false },
})
const emit = defineEmits(['progresso', 'recarregar'])

const ocupado = ref('')
const falha = ref(null)
const confirmandoTirar = ref('')
const campo = ref(null)
const alvo = ref(null)
const enviando = ref(null)

function situacao(a) {
  if (a.vencido && a.situacao !== 'pronto') return SITUACOES_DO_ANEXO.vencido
  return SITUACOES_DO_ANEXO[a.situacao] ?? { rotulo: a.situacao, classe: 'text-tinta-suave' }
}

const podeTentar = (a) => a.situacao === 'falhou' && !a.vencido
const podeEnviar = (a) => ['aguardando', 'falhou', 'vencido', 'nao_suportado', 'removido'].includes(a.situacao)
const podeTirar = (a) => a.situacao !== 'removido' && a.situacao !== 'baixando'

async function tentarDeNovo(a) {
  ocupado.value = a.id
  falha.value = null
  try {
    emit('progresso', await atendimentoApi.tentarDeNovo(props.atendimentoId, a.id))
  } catch (e) {
    falha.value = { id: a.id, texto: mensagemDe(e) }
  } finally {
    ocupado.value = ''
  }
}

function escolher(a) {
  alvo.value = a
  campo.value?.click()
}

async function enviarAMao(lista) {
  const arquivo = lista?.[0]
  const a = alvo.value
  if (campo.value) campo.value.value = ''
  if (!arquivo || !a) return
  ocupado.value = a.id
  falha.value = null
  enviando.value = { id: a.id, progresso: 0 }
  try {
    await atendimentoApi.enviarConteudo(props.atendimentoId, a.id, arquivo, (p) => (enviando.value = { id: a.id, progresso: p }))
    emit('recarregar')
  } catch (e) {
    falha.value = { id: a.id, texto: mensagemDe(e) }
  } finally {
    ocupado.value = ''
    enviando.value = null
  }
}

async function tirar(a) {
  ocupado.value = a.id
  falha.value = null
  try {
    await atendimentoApi.tirarArquivo(props.atendimentoId, a.id)
    confirmandoTirar.value = ''
    emit('recarregar')
  } catch (e) {
    falha.value = { id: a.id, texto: mensagemDe(e) }
  } finally {
    ocupado.value = ''
  }
}
</script>

<template>
  <section class="cartao" aria-label="Anexos da conversa">
    <h2 class="rotulo border-b border-linha px-5 py-2.5">Anexos da conversa</h2>
    <input ref="campo" type="file" :accept="ACEITOS_NO_ENVIO" class="sr-only" @change="enviarAMao($event.target.files)" />
    <ul class="divide-y divide-linha/70 text-sm">
      <li v-for="a in anexos" :key="a.id" class="px-5 py-2.5" :data-anexo="a.ordem">
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span class="carimbo w-6 text-tinta-apagada">{{ a.ordem }}</span>
          <span class="rounded-[2px] bg-tinta/10 px-1.5 py-0.5 text-[0.68rem] font-bold tracking-wider uppercase">{{ CATEGORIAS[a.categoria] ?? a.categoria }}</span>
          <span class="min-w-0 flex-1 truncate font-semibold" :title="a.nome">{{ a.nome }}</span>
          <span class="flex w-full flex-wrap items-center gap-x-3 gap-y-1 pl-9 text-xs sm:w-auto sm:pl-0">
            <a v-if="a.mensagemOrdem" :href="`#item-${a.mensagemOrdem}`" class="text-tinta-suave underline-offset-4 hover:underline">
              mensagem das {{ hora(a.momento) || '—' }}
            </a>
            <span v-if="a.tamanho" class="text-tinta-suave">{{ tamanho(a.tamanho) }}</span>
            <span :class="situacao(a).classe">{{ enviando?.id === a.id ? `enviando… ${Math.round((enviando.progresso ?? 0) * 100)}%` : situacao(a).rotulo }}</span>
          </span>
        </div>
        <p v-if="a.erro && a.situacao !== 'pronto'" class="mt-1 pl-9 text-xs text-carimbo">{{ a.erro }}</p>
        <div v-if="!bloqueado && (podeTentar(a) || podeEnviar(a) || podeTirar(a))" class="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 pl-9 text-xs">
          <button v-if="podeTentar(a)" type="button" class="font-semibold underline underline-offset-4" :disabled="!!ocupado" @click="tentarDeNovo(a)">
            Tentar de novo
          </button>
          <button v-if="podeEnviar(a)" type="button" class="font-semibold underline underline-offset-4" :disabled="!!ocupado" @click="escolher(a)">
            Enviar o arquivo à mão
          </button>
          <template v-if="podeTirar(a)">
            <button v-if="confirmandoTirar !== a.id" type="button" class="text-tinta-suave underline underline-offset-4" :disabled="!!ocupado"
                    @click="confirmandoTirar = a.id">Tirar</button>
            <span v-else class="flex items-center gap-2">
              <span>Tirar este anexo do atendimento?</span>
              <button type="button" class="font-semibold text-carimbo underline underline-offset-4" :disabled="!!ocupado" @click="tirar(a)">Tirar</button>
              <button type="button" class="underline underline-offset-4" @click="confirmandoTirar = ''">Não</button>
            </span>
          </template>
        </div>
        <p v-if="falha?.id === a.id" role="alert" class="mt-1 pl-9 text-xs font-semibold text-carimbo">{{ falha.texto }}</p>
      </li>
    </ul>
  </section>
</template>
