<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import SeloDaSituacao from '@/modulos/fiscal/components/SeloDaSituacao.vue'
import { estadosEmTexto, haQuanto, hora, NOMES_DOS_ESTADOS, ROTULOS_DOS_DOCUMENTOS, tempo } from '@/modulos/fiscal/formato'

/**
 * O detalhe de um estado: para cada documento, quem autoriza de verdade ("SC autoriza a NF-e na SVRS"), a situação
 * e desde quando, cada serviço lido no painel (ou o teste de conexão), o incidente aberto e a contingência. Abre por
 * cima da tela (no celular, ocupa a tela toda); Esc ou o ✕ fecham.
 */
const props = defineProps({
  uf: { type: String, required: true },
  painel: { type: Object, required: true },
  agora: { type: Date, required: true },
})
const emit = defineEmits(['fechar'])

const estado = computed(() => props.painel.estados.find((e) => e.uf === props.uf))
const autorizador = (documento, sigla) =>
  props.painel.autorizadores.find((a) => a.documento === documento && a.autorizador === sigla)

const blocos = computed(() =>
  (estado.value?.celulas ?? []).map((c) => ({ celula: c, autorizador: autorizador(c.documento, c.autorizador) })),
)
const contingencia = computed(() => {
  const c = estado.value?.contingencia
  return c ? { celula: c, autorizador: autorizador('NFE', c.autorizador) } : null
})
const outros = (a) => (a?.estados ?? []).filter((uf) => uf !== props.uf)

const fechar = ref(null)
function aoTeclar(evento) {
  if (evento.key === 'Escape') emit('fechar')
}
onMounted(async () => {
  document.addEventListener('keydown', aoTeclar)
  await nextTick()
  fechar.value?.focus()
})
onBeforeUnmount(() => document.removeEventListener('keydown', aoTeclar))
</script>

<template>
  <div class="fixed inset-0 z-40 bg-tinta/30" aria-hidden="true" @click="emit('fechar')" />
  <section
    role="dialog"
    aria-modal="true"
    :aria-label="`Detalhe de ${NOMES_DOS_ESTADOS[uf] ?? uf}`"
    class="fixed inset-y-0 right-0 z-50 flex w-full flex-col border-l border-linha bg-cartao shadow-xl sm:w-[34rem]"
  >
    <header class="flex items-start gap-3 border-b-2 border-tinta px-4 pt-4 pb-3 sm:px-5">
      <div class="min-w-0 flex-1">
        <p class="rotulo">Estado</p>
        <h2 class="mt-0.5 text-2xl leading-tight font-extrabold tracking-tight [font-stretch:85%]">
          {{ NOMES_DOS_ESTADOS[uf] ?? uf }} <span class="carimbo text-tinta-suave">{{ uf }}</span>
        </h2>
      </div>
      <button ref="fechar" type="button" class="grid size-9 shrink-0 place-items-center rounded-[3px] text-tinta-suave hover:bg-papel-escuro" aria-label="Fechar o detalhe" @click="emit('fechar')">✕</button>
    </header>

    <div class="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4 sm:px-5">
      <p v-if="!blocos.length" class="text-sm text-tinta-suave">Nenhum documento configurado para este estado.</p>

      <article v-for="b in blocos" :key="b.celula.documento" class="rounded-[3px] border border-linha bg-white/40 px-3 py-3">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h3 class="font-bold">{{ ROTULOS_DOS_DOCUMENTOS[b.celula.documento] }}</h3>
          <SeloDaSituacao :codigo="b.celula.situacao" />
          <span v-if="b.celula.desde" class="text-xs text-tinta-suave">desde {{ hora(b.celula.desde, agora) }} ({{ haQuanto(b.celula.desde, agora) }})</span>
        </div>
        <p class="mt-1 text-sm">
          {{ uf }} autoriza a {{ ROTULOS_DOS_DOCUMENTOS[b.celula.documento] }} na <strong>{{ b.celula.nome }}</strong>
          <span class="carimbo text-tinta-suave">({{ b.celula.autorizador }})</span>.
          <template v-if="outros(b.autorizador).length">
            Uma queda nela afeta também {{ estadosEmTexto(outros(b.autorizador), 8) }}.
          </template>
        </p>
        <p v-if="b.celula.detalhe" class="mt-1 text-sm text-tinta-suave">{{ b.celula.detalhe }}</p>
        <p v-if="b.autorizador?.incidente" class="mt-2 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-2 py-1.5 text-sm text-carimbo">
          Com problema desde {{ hora(b.autorizador.incidente.inicio, agora) }} ({{ haQuanto(b.autorizador.incidente.inicio, agora).replace('há ', '') }}).
          {{ b.autorizador.incidente.detalhe || 'Motivo não informado.' }}
        </p>

        <ul v-if="b.autorizador?.servicos?.length" class="mt-2 divide-y divide-linha/70 border-t border-linha/70 text-sm">
          <li v-for="s in b.autorizador.servicos" :key="s.servico" class="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 py-1.5">
            <span class="min-w-0 flex-1 font-semibold">{{ s.servico }}</span>
            <SeloDaSituacao :codigo="s.situacao" curto />
            <span class="basis-full text-xs text-tinta-suave">
              {{ s.detalhe || '—' }}
              <template v-if="s.cstat"> · cStat {{ s.cstat }}</template>
              <template v-if="s.tempoMs !== null && s.tempoMs !== undefined"> · {{ tempo(s.tempoMs) }}</template>
              <template v-if="s.verificadoEm"> · verificado às {{ hora(s.verificadoEm, agora) }}</template>
            </span>
          </li>
        </ul>
        <p v-if="b.autorizador?.origem === 'teste_conexao'" class="mt-2 text-xs text-tinta-suave">
          Sem painel público: o teste de conexão vê se o servidor responde e em quanto tempo, não se a nota passa.
        </p>
      </article>

      <article v-if="contingencia" class="rounded-[3px] border-2 border-dashed border-linha px-3 py-3">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <h3 class="font-bold">Contingência da NF-e</h3>
          <SeloDaSituacao :codigo="contingencia.celula.situacao" />
        </div>
        <p class="mt-1 text-sm">
          Se a {{ ROTULOS_DOS_DOCUMENTOS.NFE }} de {{ uf }} entrar em contingência, a autorização vai para a
          <strong>{{ contingencia.celula.nome }}</strong> <span class="carimbo text-tinta-suave">({{ contingencia.celula.autorizador }})</span>.
        </p>
        <p v-if="contingencia.celula.detalhe" class="mt-1 text-sm text-tinta-suave">{{ contingencia.celula.detalhe }}</p>
      </article>
    </div>
  </section>
</template>
