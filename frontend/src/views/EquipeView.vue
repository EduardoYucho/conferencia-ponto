<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import EstadoDaTela from '@/components/EstadoDaTela.vue'

/**
 * Equipe agora: todos os usuários, quem está trabalhando e, de quem não está, o motivo.
 *
 * A tela não decide nada: situação, rótulo e motivo de cada pessoa vêm prontos do servidor (GET /presenca).
 * Atualiza sozinha quando alguém bate o ponto (aviso em tempo real) e a cada minuto (o horário avança:
 * "antes do expediente" vira "ainda não bateu o ponto").
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const router = useRouter()

const painel = ref(null)
const erro = ref(null)
const carregando = ref(false)
const filtro = ref('todos') // 'todos' | 'online' | 'offline'
let pedido = 0
let timerMinuto = null
let timerAviso = null

async function carregar() {
  const meu = ++pedido
  carregando.value = true
  try {
    const dados = await pontoApi.presenca()
    if (meu !== pedido) return
    painel.value = dados
    erro.value = null
  } catch (e) {
    if (meu === pedido) erro.value = e
  } finally {
    if (meu === pedido) carregando.value = false
  }
}

onMounted(() => {
  carregar()
  timerMinuto = setInterval(carregar, 60_000)
})
onBeforeUnmount(() => {
  clearInterval(timerMinuto)
  clearTimeout(timerAviso)
})

// várias batidas seguidas (uma pasta de PDFs sendo lida) viram uma consulta só
watch(() => [ponto.presencaMudouEm, ponto.reconectouEm], () => {
  clearTimeout(timerAviso)
  timerAviso = setTimeout(carregar, 600)
})

const pessoas = computed(() => {
  const lista = painel.value?.pessoas ?? []
  if (filtro.value === 'online') return lista.filter((p) => p.online)
  if (filtro.value === 'offline') return lista.filter((p) => !p.online)
  return lista
})

const filtros = computed(() => [
  { valor: 'todos', rotulo: 'Todos', total: painel.value?.total },
  { valor: 'online', rotulo: 'Online', total: painel.value?.online },
  { valor: 'offline', rotulo: 'Offline', total: painel.value ? painel.value.total - painel.value.online : undefined },
])

const iniciais = (nome) => (nome ?? '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase()
const hhmm = (hora) => (hora ?? '').slice(0, 5)

/** Cor do ponto de situação: verde trabalhando, âmbar intervalo ou atenção, cinza o resto. */
function corDo(p) {
  if (p.online) return 'bg-credito'
  if (p.situacao === 'INTERVALO') return 'bg-amber-500'
  if (p.atencao) return 'bg-carimbo'
  return 'bg-tinta-apagada'
}

/** Administração e coordenação abrem o ponto da pessoa a partir do cartão dela. */
const podeAbrir = (p) => auth.podeVerTodos && p.login && p.situacao !== 'NAO_REGISTRA_PONTO'
function abrir(p) {
  auth.verComo(p.login)
  router.push(auth.ehTitular ? { name: 'painel' } : { name: 'auditoria' })
}
</script>

<template>
  <main class="mx-auto max-w-6xl px-4 py-6 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
      <div>
        <p class="rotulo">Quem está trabalhando</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%]">Equipe agora</h1>
        <p class="mt-2 text-sm text-tinta-suave">
          Online é quem bateu a entrada e ainda não bateu a saída. Quem está offline aparece com o motivo.
        </p>
      </div>
      <p v-if="painel" class="text-sm text-tinta-suave" role="status">
        Situação das <span class="carimbo font-semibold text-tinta">{{ hhmm(painel.agora) }}</span>
        <button type="button" class="ml-2 font-semibold text-tinta underline underline-offset-4" :disabled="carregando" @click="carregar">
          {{ carregando ? 'atualizando…' : 'atualizar' }}
        </button>
      </p>
    </header>

    <!-- Resumo: o número que importa primeiro -->
    <section v-if="painel" class="mt-5 grid grid-cols-3 gap-3" aria-label="Resumo da equipe">
      <div class="cartao px-4 py-3">
        <p class="rotulo">Online</p>
        <p class="carimbo mt-1 text-3xl font-semibold text-credito">{{ painel.online }}</p>
      </div>
      <div class="cartao px-4 py-3">
        <p class="rotulo">Em intervalo</p>
        <p class="carimbo mt-1 text-3xl font-semibold text-amber-700">{{ painel.emIntervalo }}</p>
      </div>
      <div class="cartao px-4 py-3">
        <p class="rotulo">Offline</p>
        <p class="carimbo mt-1 text-3xl font-semibold text-tinta-suave">{{ painel.offline }}</p>
      </div>
    </section>

    <div v-if="painel" class="mt-5 flex flex-wrap gap-1.5" role="group" aria-label="Filtrar a lista">
      <button
        v-for="f in filtros"
        :key="f.valor"
        type="button"
        class="rounded-full border px-3 py-1 text-sm font-semibold transition"
        :class="filtro === f.valor ? 'border-tinta bg-tinta text-cartao' : 'border-linha bg-cartao text-tinta-suave hover:border-tinta hover:text-tinta'"
        :aria-pressed="filtro === f.valor"
        @click="filtro = f.valor"
      >{{ f.rotulo }} <span class="carimbo ml-0.5 font-normal opacity-80">{{ f.total }}</span></button>
    </div>

    <div class="mt-4">
      <EstadoDaTela
        :carregando="carregando"
        :erro="erro"
        :manter="!!painel"
        :vazio="!!painel && !pessoas.length"
        carregando-texto="Consultando a situação da equipe…"
        @tentar="carregar"
      >
        <p v-if="painel && !pessoas.length" class="py-6 text-center text-sm text-tinta-suave">
          {{ filtro === 'online' ? 'Ninguém está trabalhando neste momento.' : 'Ninguém nesta situação agora.' }}
        </p>
        <ul v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <li
            v-for="p in pessoas"
            :key="p.id"
            class="cartao flex gap-3 px-4 py-3"
            :class="{ 'border-credito/50': p.online, 'border-carimbo/50': p.atencao }"
          >
            <span class="relative mt-0.5 size-11 shrink-0 self-start">
              <span
                class="grid size-11 place-items-center rounded-full text-sm font-bold"
                :class="p.online ? 'bg-credito text-white' : 'bg-papel-escuro text-tinta-suave'"
                aria-hidden="true"
              >{{ iniciais(p.nome) }}</span>
              <span class="absolute -right-0.5 -bottom-0.5 size-3.5 rounded-full border-2 border-cartao" :class="corDo(p)" aria-hidden="true" />
            </span>

            <div class="min-w-0 flex-1">
              <p class="flex flex-wrap items-baseline gap-x-2">
                <span class="truncate font-semibold">{{ p.nome }}</span>
                <span v-if="p.euMesmo" class="text-xs text-tinta-suave">você</span>
              </p>
              <p class="mt-0.5 text-sm font-bold tracking-wide uppercase" :class="p.online ? 'text-credito' : 'text-tinta-suave'">
                {{ p.online ? 'Online' : 'Offline' }}
              </p>
              <p class="mt-1 text-sm" :class="p.atencao ? 'font-semibold text-carimbo' : 'text-tinta'">{{ p.motivo }}</p>
              <p v-if="p.detalhe" class="mt-0.5 text-sm text-tinta-suave">{{ p.detalhe }}</p>
              <p v-if="p.alemDoHorario && p.horario" class="mt-0.5 text-sm text-tinta-suave">Fora do horário de hoje</p>

              <dl class="mt-2 space-y-0.5 text-xs text-tinta-suave">
                <div v-if="p.horario">
                  <dt class="inline">Horário de hoje:</dt>
                  <dd class="carimbo ml-1 inline">{{ p.horario }}</dd>
                </div>
                <div v-if="p.batidas?.length">
                  <dt class="inline">Batidas de hoje:</dt>
                  <dd class="carimbo ml-1 inline">{{ p.batidas.map(hhmm).join(' · ') }}</dd>
                </div>
              </dl>

              <button
                v-if="podeAbrir(p) && !p.euMesmo"
                type="button"
                class="mt-2 text-xs font-semibold underline underline-offset-4 hover:text-carimbo"
                @click="abrir(p)"
              >ver o ponto de {{ p.nome.split(' ')[0] }}</button>
            </div>
          </li>
        </ul>
      </EstadoDaTela>
    </div>

    <p v-if="painel" class="mt-6 text-xs text-tinta-suave">
      A situação vem das batidas de hoje e das folgas, férias e feriados cadastrados. Quem importa o ponto por
      comprovante (PDF) aparece online assim que o comprovante da entrada chega ao sistema.
      <template v-if="!auth.podeVerTodos">Atestados e abonos de colegas aparecem só como "Ausência justificada".</template>
    </p>
  </main>
</template>
