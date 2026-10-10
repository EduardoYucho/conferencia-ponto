<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { atendimentoApi } from '@/modulos/atendimento/api'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'
import {
  CATEGORIAS, dataHora, hora, periodo, SITUACOES, SITUACOES_DO_ANEXO, textoDosOmitidos, validadeDosLinks,
} from '@/modulos/atendimento/formato'

/**
 * Um atendimento: a conversa como o servidor leu do PDF (já sem a chave do bot e sem dados de acesso remoto,
 * que aparecem como [omitido]) e os anexos com a validade dos links. O link em si nunca chega à tela.
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

async function carregar() {
  carregando.value = true
  erro.value = null
  try {
    atendimento.value = await atendimentoApi.atendimento(props.id)
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)
watch(() => props.id, carregar)

const situacao = computed(() => SITUACOES[atendimento.value?.situacao] ?? SITUACOES.novo)
const mensagens = computed(() => atendimento.value?.itens.filter((i) => i.tipo === 'mensagem').length ?? 0)
const eventos = computed(() => (atendimento.value?.itens.length ?? 0) - mensagens.value)
const anexosPorOrdem = computed(() => Object.fromEntries((atendimento.value?.anexos ?? []).map((a) => [a.ordem, a])))

/** Texto em pedaços, para destacar o que foi omitido. */
function partes(texto) {
  return (texto ?? '').split(OMITIDO).flatMap((pedaco, i) => (i === 0 ? [{ texto: pedaco }] : [{ omitido: true }, { texto: pedaco }]))
    .filter((p) => p.omitido || p.texto)
}

function situacaoDoAnexo(anexo) {
  if (anexo.vencido) return 'link vencido'
  return SITUACOES_DO_ANEXO[anexo.situacao] ?? anexo.situacao
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
        <div v-if="atendimento" class="grid gap-5">
          <!-- resumo -->
          <section class="cartao px-5 py-4" aria-label="Resumo">
            <dl class="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
              <div>
                <dt class="rotulo">Conversa</dt>
                <dd class="mt-0.5">{{ mensagens }} mensagens · {{ eventos }} eventos</dd>
              </div>
              <div>
                <dt class="rotulo">Anexos</dt>
                <dd class="mt-0.5">
                  {{ atendimento.anexos.length }}
                  <span v-if="atendimento.anexos.length" :class="atendimento.linksVencidos ? 'font-semibold text-carimbo' : 'text-tinta-suave'">
                    · {{ validadeDosLinks(atendimento.linksValidosAte, atendimento.linksVencidos) }}
                  </span>
                </dd>
              </div>
              <div>
                <dt class="rotulo">Arquivos guardados até</dt>
                <dd class="mt-0.5">{{ dataHora(atendimento.apagarArquivosEm) }}</dd>
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

          <!-- anexos -->
          <section v-if="atendimento.anexos.length" class="cartao" aria-label="Anexos da conversa">
            <h2 class="rotulo border-b border-linha px-5 py-2.5">Anexos da conversa</h2>
            <ul class="divide-y divide-linha/70 text-sm">
              <li v-for="a in atendimento.anexos" :key="a.id" class="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-2.5">
                <span class="carimbo w-6 text-tinta-apagada">{{ a.ordem }}</span>
                <span class="rounded-[2px] bg-tinta/10 px-1.5 py-0.5 text-[0.68rem] font-bold tracking-wider uppercase">{{ CATEGORIAS[a.categoria] ?? a.categoria }}</span>
                <span class="min-w-0 flex-1 truncate font-semibold" :title="a.nome">{{ a.nome }}</span>
                <span class="flex w-full flex-wrap gap-x-3 pl-9 text-xs sm:w-auto sm:pl-0">
                  <a v-if="a.mensagemOrdem" :href="`#item-${a.mensagemOrdem}`" class="text-tinta-suave underline-offset-4 hover:underline">
                    mensagem das {{ hora(a.momento) || '—' }}
                  </a>
                  <span :class="a.vencido ? 'font-semibold text-carimbo' : 'text-tinta-suave'">{{ situacaoDoAnexo(a) }}</span>
                </span>
              </li>
            </ul>
            <p class="border-t border-linha px-5 py-2.5 text-xs text-tinta-suave">
              O download dos anexos e o envio de ligações, vídeo e prints chegam na próxima versão do gerador.
            </p>
          </section>

          <!-- conversa -->
          <section class="cartao px-3 py-4 sm:px-5" aria-label="Conversa">
            <h2 class="rotulo mb-3 px-2 sm:px-0">Conversa lida do PDF</h2>
            <ol class="grid gap-2.5">
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
                    <ul v-if="item.anexos.length" class="mt-1.5 grid gap-1">
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
