<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { conectarEventosDoGerador } from '@/modulos/atendimento/eventos'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import AnexosDaConversa from '@/modulos/atendimento/components/AnexosDaConversa.vue'
import ArquivosEnviados from '@/modulos/atendimento/components/ArquivosEnviados.vue'
import ProcessamentoDoAtendimento from '@/modulos/atendimento/components/ProcessamentoDoAtendimento.vue'
import { mensagemDe } from '@/utils/erros'
import { CATEGORIAS, contagem, dataHora, hora, periodo, SITUACOES, textoDosOmitidos } from '@/modulos/atendimento/formato'

/**
 * Um atendimento: o processamento ao vivo (o download dos anexos), os anexos da conversa, as ligações, o vídeo e os
 * prints enviados pela pessoa, e a conversa como o servidor leu do PDF (já sem a chave do bot e sem dados de acesso
 * remoto, que aparecem como [omitido]). O link de download nunca chega à tela.
 */
const props = defineProps({
  id: { type: String, required: true },
})
const router = useRouter()

const atendimento = ref(null)
const carregando = ref(false)
const erro = ref(null)

const confirmandoApagar = ref(false)
const apagando = ref(false)
const erroAoApagar = ref('')

const OMITIDO = '[omitido]'

async function carregar({ silencioso = false } = {}) {
  if (!silencioso) carregando.value = true
  erro.value = null
  try {
    atendimento.value = await atendimentoApi.atendimento(props.id)
  } catch (e) {
    if (!silencioso || !atendimento.value) erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(() => carregar())
watch(() => props.id, () => carregar())

/** Aplica o progresso (da ação ou do SSE) sem recarregar a conversa; um arquivo que a tela não conhece recarrega tudo. */
let recarga = null
function aplicarProgresso(progresso) {
  const a = atendimento.value
  if (!a || progresso?.atendimentoId !== a.id) return
  a.situacao = progresso.situacao
  a.motivoPausa = progresso.motivoPausa
  a.percentual = progresso.percentual
  const porId = new Map([...a.anexos, ...a.extras].map((x) => [x.id, x]))
  let desconhecido = false
  for (const arquivo of progresso.arquivos ?? []) {
    const local = porId.get(arquivo.id)
    if (!local) {
      desconhecido = true
      continue
    }
    local.situacao = arquivo.situacao
    local.erro = arquivo.erro
    local.tamanho = arquivo.tamanho
    if (arquivo.situacao === 'pronto') local.vencido = false
  }
  if (desconhecido || (progresso.arquivos ?? []).length !== porId.size) {
    clearTimeout(recarga)
    recarga = setTimeout(() => carregar({ silencioso: true }), 300)
  }
}

let desligar = null
onMounted(() => {
  desligar = conectarEventosDoGerador({ onProgresso: aplicarProgresso })
})
onUnmounted(() => {
  desligar?.()
  clearTimeout(recarga)
})

const situacao = computed(() => SITUACOES[atendimento.value?.situacao] ?? SITUACOES.novo)
const mensagens = computed(() => atendimento.value?.itens.filter((i) => i.tipo === 'mensagem').length ?? 0)
const eventos = computed(() => (atendimento.value?.itens.length ?? 0) - mensagens.value)
const anexosPorOrdem = computed(() => Object.fromEntries((atendimento.value?.anexos ?? []).map((a) => [a.ordem, a])))
const arquivosApagados = computed(() => !!atendimento.value?.arquivosApagadosEm)
const noServidor = computed(() => atendimento.value?.anexos.filter((a) => a.situacao === 'pronto').length ?? 0)

/** Texto em pedaços, para destacar o que foi omitido. */
function partes(texto) {
  return (texto ?? '').split(OMITIDO).flatMap((pedaco, i) => (i === 0 ? [{ texto: pedaco }] : [{ omitido: true }, { texto: pedaco }]))
    .filter((p) => p.omitido || p.texto)
}

async function apagar() {
  apagando.value = true
  erroAoApagar.value = ''
  try {
    await atendimentoApi.apagarAtendimento(props.id)
    router.push({ name: 'atendimentos' })
  } catch (e) {
    erroAoApagar.value = mensagemDe(e)
  } finally {
    apagando.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <RouterLink :to="{ name: 'atendimentos' }" class="rotulo hover:text-tinta">← Atendimentos</RouterLink>
      <template v-if="atendimento">
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight break-words [font-stretch:80%] sm:text-4xl">
          {{ atendimento.contato ?? 'Contato sem nome' }}
        </h1>
        <p class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-tinta-suave">
          <span>Chamado <span class="carimbo text-tinta">{{ atendimento.chamado }}</span></span>
          <span>{{ periodo(atendimento.inicio, atendimento.fim) }}</span>
          <span class="rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacao.classe">{{ situacao.rotulo }}</span>
        </p>
      </template>
      <h1 v-else class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Atendimento</h1>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="carregando" :erro="erro" carregando-texto="Abrindo o atendimento…" @tentar="carregar">
        <div v-if="atendimento" class="grid grid-cols-[minmax(0,1fr)] gap-5">
          <!-- resumo -->
          <section class="cartao px-5 py-4" aria-label="Resumo">
            <dl class="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
              <div>
                <dt class="rotulo">Conversa</dt>
                <dd class="mt-0.5">{{ contagem(mensagens, 'mensagem', 'mensagens') }} · {{ contagem(eventos, 'evento', 'eventos') }}</dd>
              </div>
              <div>
                <dt class="rotulo">Anexos</dt>
                <dd class="mt-0.5">
                  {{ atendimento.anexos.length }}
                  <span v-if="atendimento.anexos.length" class="text-tinta-suave">· {{ noServidor }} no servidor</span>
                </dd>
              </div>
              <div>
                <dt class="rotulo">Arquivos guardados até</dt>
                <dd class="mt-0.5">{{ arquivosApagados ? 'já apagados' : dataHora(atendimento.apagarArquivosEm) }}</dd>
              </div>
              <div v-if="atendimento.assunto" class="sm:col-span-3">
                <dt class="rotulo">Assunto (no Digisac)</dt>
                <dd class="mt-0.5 whitespace-pre-line">{{ atendimento.assunto }}</dd>
              </div>
              <div v-if="atendimento.resumo" class="sm:col-span-3">
                <dt class="rotulo">Resumo (no Digisac)</dt>
                <dd class="mt-0.5 whitespace-pre-line">{{ atendimento.resumo }}</dd>
              </div>
            </dl>
            <p class="mt-3 text-xs text-tinta-suave">{{ textoDosOmitidos(atendimento.omitidos) }}</p>
          </section>

          <p v-if="arquivosApagados" role="status" class="rounded-[3px] border border-linha bg-papel-escuro/40 px-3 py-2 text-sm">
            Os arquivos deste atendimento (PDF, anexos, ligações, vídeo e prints) foram apagados em
            {{ dataHora(atendimento.arquivosApagadosEm) }}, pela retenção. A conversa lida continua aqui.
          </p>

          <ProcessamentoDoAtendimento :atendimento="atendimento" @progresso="aplicarProgresso" @recarregar="carregar({ silencioso: true })" />

          <AnexosDaConversa
            v-if="atendimento.anexos.length"
            :atendimento-id="atendimento.id"
            :anexos="atendimento.anexos"
            :bloqueado="arquivosApagados"
            @progresso="aplicarProgresso"
            @recarregar="carregar({ silencioso: true })"
          />

          <ArquivosEnviados
            :atendimento-id="atendimento.id"
            :extras="atendimento.extras"
            :bloqueado="arquivosApagados"
            @recarregar="carregar({ silencioso: true })"
          />

          <!-- conversa -->
          <section class="cartao px-3 py-4 sm:px-5" aria-label="Conversa">
            <h2 class="rotulo mb-3 px-2 sm:px-0">Conversa lida do PDF</h2>
            <ol class="grid grid-cols-[minmax(0,1fr)] gap-2.5">
              <li v-for="item in atendimento.itens" :id="`item-${item.ordem}`" :key="item.ordem" class="scroll-mt-20">
                <p v-if="item.tipo === 'evento'" class="mx-auto max-w-xl px-2 text-center text-xs text-tinta-suave">
                  <span class="whitespace-pre-line">{{ item.texto }}</span>
                </p>
                <div v-else class="flex" :class="item.lado === 'cliente' ? 'justify-start' : 'justify-end'">
                  <div
                    class="max-w-[85%] rounded-[3px] border px-3 py-2 text-sm sm:max-w-[70%]"
                    :class="item.lado === 'cliente' ? 'border-linha bg-white/70' : 'border-tinta/20 bg-papel-escuro/60'"
                  >
                    <p class="flex items-baseline justify-between gap-3 text-xs">
                      <span class="min-w-0 truncate font-semibold">{{ item.remetente ?? 'Sem remetente' }}</span>
                      <span class="carimbo shrink-0 text-tinta-suave">{{ hora(item.momento) }}</span>
                    </p>
                    <p v-if="item.texto" class="mt-1 break-words whitespace-pre-wrap">
                      <template v-for="(p, i) in partes(item.texto)" :key="i">
                        <span v-if="p.omitido" class="rounded-[2px] bg-tinta/10 px-1 font-mono text-xs text-tinta-suave" title="Retirado da conversa pelo sistema">[omitido]</span>
                        <template v-else>{{ p.texto }}</template>
                      </template>
                    </p>
                    <ul v-if="item.anexos.length" class="mt-1.5 grid grid-cols-[minmax(0,1fr)] gap-1">
                      <li v-for="o in item.anexos" :key="o" class="flex items-center gap-2 rounded-[2px] border border-linha bg-cartao px-2 py-1 text-xs">
                        <span class="font-bold tracking-wider uppercase">{{ CATEGORIAS[anexosPorOrdem[o]?.categoria] ?? 'Anexo' }}</span>
                        <span class="min-w-0 truncate" :title="anexosPorOrdem[o]?.nome">{{ anexosPorOrdem[o]?.nome ?? `anexo ${o}` }}</span>
                      </li>
                    </ul>
                  </div>
                </div>
              </li>
            </ol>
          </section>

          <!-- apagar -->
          <section class="cartao px-5 py-4" aria-label="Apagar o atendimento">
            <div v-if="!confirmandoApagar" class="flex flex-wrap items-center justify-between gap-3">
              <p class="text-sm text-tinta-suave">Apagar tira a conversa e os arquivos deste atendimento do servidor.</p>
              <button type="button" class="botao-secundario" @click="confirmandoApagar = true">Apagar atendimento</button>
            </div>
            <div v-else role="alertdialog" aria-label="Confirmar a exclusão">
              <p class="text-sm font-semibold">Apagar o atendimento do chamado {{ atendimento.chamado }}? Não dá para desfazer.</p>
              <div class="mt-3 flex flex-wrap gap-3">
                <button type="button" class="botao-secundario border-carimbo! text-carimbo!" :disabled="apagando" @click="apagar">
                  {{ apagando ? 'Apagando…' : 'Apagar de vez' }}
                </button>
                <button type="button" class="botao-secundario" :disabled="apagando" @click="confirmandoApagar = false">Cancelar</button>
              </div>
              <p v-if="erroAoApagar" role="alert" class="mt-2 text-sm text-carimbo">{{ erroAoApagar }}</p>
            </div>
          </section>
        </div>
      </EstadoDaTela>
    </div>
  </div>
</template>
