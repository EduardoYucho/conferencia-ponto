<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'

/**
 * Equipe: todos os usuários, separados em quem está trabalhando agora, quem está em intervalo e quem está
 * fora (com o motivo).
 *
 * A tela não decide nada: a situação, o motivo e os totais de cada grupo vêm prontos do servidor (GET /presenca).
 * Atualiza sozinha quando alguém bate o ponto (aviso em tempo real) e a cada minuto (o horário avança:
 * "antes do expediente" vira "ainda não bateu o ponto").
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const router = useRouter()

const painel = ref(null)
const erro = ref(null)
const carregando = ref(false)
/** 'todos' ou a chave de um dos grupos. */
const filtro = ref('todos')
let pedido = 0
let timerMinuto = null
let timerAviso = null

/** @param {{ silencioso?: boolean }} [opcoes] silencioso: atualização automática (o botão não fica "Atualizando…") */
async function carregar({ silencioso = false } = {}) {
  const meu = ++pedido
  if (!silencioso) carregando.value = true
  try {
    const dados = await pontoApi.presenca()
    if (meu !== pedido) return
    painel.value = dados
    erro.value = null
  } catch (e) {
    // a falha aparece mesmo na atualização automática: o que está na tela ficou parado no horário mostrado
    if (meu === pedido) erro.value = e
  } finally {
    if (meu === pedido) carregando.value = false
  }
}

onMounted(() => {
  carregar()
  timerMinuto = setInterval(() => carregar({ silencioso: true }), 60_000)
})
onBeforeUnmount(() => {
  pedido++ // uma resposta que ainda está a caminho não entra numa tela que já saiu
  clearInterval(timerMinuto)
  clearTimeout(timerAviso)
})

// várias batidas seguidas (uma pasta de PDFs sendo lida) viram uma consulta só
watch(() => [ponto.presencaMudouEm, ponto.reconectouEm], () => {
  clearTimeout(timerAviso)
  timerAviso = setTimeout(() => carregar({ silencioso: true }), 600)
})

/**
 * Os três grupos da tela. Em qual deles a pessoa entra sai do que o servidor diz dela (`online`, `situacao`);
 * o total de cada um também vem do servidor.
 */
const GRUPOS = [
  {
    chave: 'trabalhando',
    titulo: 'Trabalhando agora',
    selo: 'Trabalhando',
    classeSelo: 'selo-positivo',
    vazio: 'Ninguém está trabalhando neste momento.',
    total: (p) => p.online,
    pertence: (p) => p.online,
  },
  {
    chave: 'intervalo',
    titulo: 'Em intervalo',
    selo: 'Em intervalo',
    classeSelo: 'selo-info',
    vazio: 'Ninguém está em intervalo agora.',
    total: (p) => p.emIntervalo,
    pertence: (p) => !p.online && p.situacao === 'INTERVALO',
  },
  {
    chave: 'fora',
    titulo: 'Fora',
    selo: 'Fora',
    classeSelo: 'selo-neutro',
    vazio: 'Ninguém está fora agora.',
    total: (p) => p.offline,
    pertence: (p) => !p.online && p.situacao !== 'INTERVALO',
  },
]

const grupos = computed(() => GRUPOS.map((g) => ({
  ...g,
  total: painel.value ? g.total(painel.value) : 0,
  pessoas: (painel.value?.pessoas ?? []).filter(g.pertence),
})))

/** Com "Todos", os grupos vazios não ocupam a tela (menos o primeiro: é a pergunta que a tela responde). */
const gruposVisiveis = computed(() => grupos.value.filter((g) => (filtro.value === 'todos'
  ? g.pessoas.length > 0 || g.chave === 'trabalhando'
  : g.chave === filtro.value)))

const filtros = computed(() => [
  { valor: 'todos', rotulo: 'Todos', total: painel.value?.total },
  ...grupos.value.map((g) => ({ valor: g.chave, rotulo: g.titulo, total: g.total })),
])

const iniciais = (nome) => (nome ?? '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase()
const hhmm = (hora) => (hora ?? '').slice(0, 5)

/** Administração e coordenação abrem o ponto da pessoa a partir do cartão dela. */
const podeAbrir = (p) => auth.podeVerTodos && p.login && p.situacao !== 'NAO_REGISTRA_PONTO'
function abrir(p) {
  auth.verComo(p.login)
  router.push({ name: 'meu-ponto' })
}
</script>

<template>
  <main class="pagina">
    <header class="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
      <div>
        <h1 class="titulo-pagina">Equipe agora</h1>
        <p class="subtitulo-pagina">Quem está trabalhando neste momento. Quem não está aparece com o motivo.</p>
      </div>
      <div v-if="painel" class="flex flex-wrap items-center gap-x-4 gap-y-2">
        <p class="text-[0.95rem] text-texto-3" role="status">
          Atualizado às <b class="text-texto">{{ hhmm(painel.agora) }}</b> · atualiza sozinho
        </p>
        <button type="button" class="botao-secundario min-h-11" :disabled="carregando" @click="carregar()">
          <Icone nome="atualizar" tamanho="18" /> {{ carregando ? 'Atualizando…' : 'Atualizar agora' }}
        </button>
      </div>
    </header>

    <EstadoDaTela
      :carregando="carregando"
      :erro="erro"
      :manter="!!painel"
      carregando-texto="Consultando a situação da equipe…"
      @tentar="carregar()"
    />

    <template v-if="painel">
      <!-- Contadores: no celular, um por linha (nome à esquerda, número à direita); em tela larga, lado a lado -->
      <section class="grid gap-3 sm:grid-cols-3 sm:gap-4" aria-label="Resumo da equipe">
        <div
          v-for="g in grupos"
          :key="g.chave"
          class="flex items-center justify-between gap-3 rounded-2xl border px-5 py-3 sm:flex-col sm:items-start sm:justify-start sm:gap-0 sm:py-4"
          :class="g.chave === 'trabalhando' ? 'border-positivo-borda bg-positivo-suave text-positivo' : 'border-borda bg-superficie'"
        >
          <h2 class="text-[0.95rem] font-semibold" :class="{ 'text-texto-3': g.chave !== 'trabalhando' }">{{ g.titulo }}</h2>
          <p class="text-[2rem] leading-tight font-extrabold tracking-tight">{{ g.total }}</p>
        </div>
      </section>

      <div class="flex flex-wrap gap-2" role="group" aria-label="Mostrar">
        <button
          v-for="f in filtros"
          :key="f.valor"
          type="button"
          class="pilula"
          :class="{ 'pilula-ativa': filtro === f.valor }"
          :aria-pressed="filtro === f.valor"
          @click="filtro = f.valor"
        >{{ f.rotulo }} ({{ f.total }})</button>
      </div>

      <section v-for="g in gruposVisiveis" :key="g.chave" :aria-labelledby="`grupo-${g.chave}`" class="flex flex-col gap-3">
        <h2 :id="`grupo-${g.chave}`" class="titulo-secao">
          {{ g.titulo }} <span class="text-[0.95rem] font-semibold text-texto-3">({{ g.total }})</span>
        </h2>

        <p v-if="!g.pessoas.length" class="cartao px-5 py-6 text-center text-[0.95rem] text-texto-3">{{ g.vazio }}</p>

        <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(18.5rem,1fr))] gap-4">
          <li
            v-for="p in g.pessoas"
            :key="p.id"
            class="flex gap-3.5 rounded-2xl border px-5 py-4"
            :class="p.atencao ? 'border-atencao-borda bg-atencao-suave'
              : p.online ? 'border-positivo-borda bg-superficie' : 'border-borda bg-superficie'"
          >
            <span
              class="grid size-12 shrink-0 place-items-center rounded-full font-bold"
              :class="p.online ? 'bg-positivo-solido text-white'
                : p.atencao ? 'bg-atencao-borda text-atencao' : 'bg-neutro text-texto-2'"
              aria-hidden="true"
            >{{ iniciais(p.nome) }}</span>

            <div class="flex min-w-0 flex-1 flex-col items-start gap-1">
              <h3 class="max-w-full text-[1.05rem] font-bold break-words">
                {{ p.nome }} <span v-if="p.euMesmo" class="text-sm font-medium text-texto-3">você</span>
              </h3>
              <span class="selo" :class="p.atencao ? 'bg-superficie text-atencao' : g.classeSelo">{{ g.selo }}</span>

              <p class="text-[0.95rem]" :class="p.atencao ? 'font-semibold text-atencao' : 'text-texto'">{{ p.motivo }}</p>
              <p v-if="p.detalhe" class="text-sm text-texto-2">{{ p.detalhe }}</p>
              <p v-if="p.alemDoHorario && p.horario" class="text-sm text-texto-2">Fora do horário de hoje</p>
              <p v-if="p.horario" class="text-sm" :class="p.atencao ? 'text-atencao' : 'text-texto-3'">Horário de hoje: {{ p.horario }}</p>
              <p v-if="p.batidas?.length" class="text-sm" :class="p.atencao ? 'text-atencao' : 'text-texto-3'">
                Batidas de hoje: {{ p.batidas.map(hhmm).join(' · ') }}
              </p>

              <button
                v-if="podeAbrir(p) && !p.euMesmo"
                type="button"
                class="botao-linha mt-1.5"
                :aria-label="`Ver o ponto de ${p.nome}`"
                @click="abrir(p)"
              ><Icone nome="calendario" tamanho="18" /> Ver o ponto</button>
            </div>
          </li>
        </ul>
      </section>

      <section class="cartao px-5 py-4 sm:px-6" aria-labelledby="titulo-como-equipe">
        <h2 id="titulo-como-equipe" class="text-base font-extrabold">De onde vem esta situação</h2>
        <p class="mt-1.5 text-[0.95rem] leading-relaxed text-texto-2">
          Das batidas de hoje e das folgas, férias e feriados cadastrados. Quem recebe o ponto por comprovante (PDF)
          aparece trabalhando assim que o comprovante da entrada chega ao sistema.
          <template v-if="!auth.podeVerTodos">Atestados e outras justificativas de colegas aparecem só como "Ausência justificada".</template>
        </p>
      </section>
    </template>
  </main>
</template>
