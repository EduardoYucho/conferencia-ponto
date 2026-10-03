<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'

/** Sino com os avisos do sistema (prazo do banco de horas, relatório do RH conferido). */
const notificacoes = useNotificacoesStore()
const { lista, naoLidas, recemChegada, carregado } = storeToRefs(notificacoes)
const router = useRouter()

const aberto = ref(false)
const raiz = ref(null)
const pulsando = ref(false)
const carregando = ref(false)
const erroCarga = ref(null)
/** "Marcar todos como lidos" não funcionou (a frase aparece dentro do painel). */
const falhaAoMarcar = ref('')
let timerPulso = null

/** Enquanto os avisos não chegaram, o painel não pode dizer "nenhum aviso": ou está carregando, ou falhou. */
const falhouCarga = computed(() => !carregado.value && !!erroCarga.value)

async function carregar() {
  carregando.value = true
  erroCarga.value = null
  try {
    await notificacoes.carregar()
  } catch (e) {
    erroCarga.value = e
  } finally {
    carregando.value = false
  }
}

async function marcarTodas() {
  falhaAoMarcar.value = ''
  try {
    await notificacoes.marcarTodas()
  } catch (e) {
    falhaAoMarcar.value = mensagemDe(e)
  }
}

onMounted(() => {
  carregar()
  document.addEventListener('mousedown', aoClicarFora)
  window.addEventListener('keydown', aoTeclar)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', aoClicarFora)
  window.removeEventListener('keydown', aoTeclar)
  clearTimeout(timerPulso)
})

// a falha de uma tentativa anterior não fica esperando no painel reaberto
watch(aberto, (abriu) => abriu && (falhaAoMarcar.value = ''))

watch(recemChegada, (n) => {
  if (!n) return
  pulsando.value = true
  clearTimeout(timerPulso)
  timerPulso = setTimeout(() => (pulsando.value = false), 4000)
})

function aoClicarFora(evento) {
  if (aberto.value && raiz.value && !raiz.value.contains(evento.target)) aberto.value = false
}

function aoTeclar(evento) {
  if (evento.key === 'Escape') aberto.value = false
}

async function abrirNotificacao(n) {
  aberto.value = false
  if (!n.lida) notificacoes.marcarLida(n.id).catch(() => {})
  if (n.link) router.push(n.link)
}

const quando = (iso) => {
  const minutos = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (minutos < 1) return 'agora'
  if (minutos < 60) return `há ${minutos} min`
  if (minutos < 60 * 24) return `há ${Math.round(minutos / 60)} h`
  return new Date(iso).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })
}

const icone = (tipo) => (tipo === 'CONCILIACAO' ? '⇄' : tipo === 'CICLO_VENCIDO' ? '!' : '⏳')
</script>

<template>
  <div ref="raiz" class="relative">
    <button
      type="button"
      class="relative grid size-8 place-items-center rounded-full text-tinta-suave transition hover:bg-papel-escuro hover:text-tinta"
      :class="{ 'animate-pulse text-carimbo': pulsando }"
      :aria-label="naoLidas ? `${naoLidas} aviso(s) não lido(s)` : 'Avisos'"
      :aria-expanded="aberto"
      @click="aberto = !aberto"
    >
      <svg viewBox="0 0 20 20" class="size-5" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
        <path d="M5 8a5 5 0 0 1 10 0v3.5l1.5 2.5h-13L5 11.5V8Z" stroke-linejoin="round" />
        <path d="M8 16.5a2 2 0 0 0 4 0" stroke-linecap="round" />
      </svg>
      <span
        v-if="naoLidas"
        class="carimbo absolute -top-0.5 -right-0.5 grid min-w-4 place-items-center rounded-full bg-carimbo px-1 text-[0.6rem] font-bold text-cartao"
      >{{ naoLidas > 9 ? '9+' : naoLidas }}</span>
    </button>

    <Transition
      enter-active-class="transition duration-150"
      enter-from-class="-translate-y-1 opacity-0"
      leave-active-class="transition duration-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="aberto"
        class="cartao absolute right-0 z-50 mt-2 w-[min(22rem,calc(100vw-2rem))] overflow-hidden"
        role="dialog"
        aria-label="Avisos"
      >
        <div class="flex items-center justify-between border-b border-linha px-4 py-2.5">
          <p class="rotulo">Avisos</p>
          <button
            v-if="naoLidas"
            type="button"
            class="text-xs font-semibold text-tinta-suave underline underline-offset-4 hover:text-tinta"
            @click="marcarTodas"
          >marcar todos como lidos</button>
        </div>
        <p v-if="falhaAoMarcar" role="alert" class="border-b border-linha px-4 py-2 text-xs text-carimbo">{{ falhaAoMarcar }}</p>
        <div v-if="falhouCarga" class="px-4 py-3">
          <EstadoDaTela :erro="erroCarga" :carregando="carregando" @tentar="carregar" />
        </div>
        <ul class="max-h-96 divide-y divide-linha/70 overflow-y-auto">
          <li v-if="!lista.length && !falhouCarga" class="px-4 py-6 text-center text-sm text-tinta-suave">
            {{ carregado ? 'Nenhum aviso por enquanto.' : 'Carregando…' }}
          </li>
          <li v-for="n in lista" :key="n.id">
            <button
              type="button"
              class="flex w-full gap-3 px-4 py-3 text-left transition hover:bg-papel"
              :class="n.lida ? 'opacity-60' : ''"
              @click="abrirNotificacao(n)"
            >
              <span
                class="mt-0.5 grid size-6 shrink-0 place-items-center rounded-full text-xs font-bold"
                :class="n.tipo === 'CONCILIACAO' ? 'bg-tinta/10 text-tinta' : 'bg-carimbo/10 text-carimbo'"
                aria-hidden="true"
              >{{ icone(n.tipo) }}</span>
              <span class="min-w-0">
                <span class="flex items-baseline gap-2">
                  <span class="text-sm font-semibold">{{ n.titulo }}</span>
                  <span v-if="!n.lida" class="size-1.5 shrink-0 rounded-full bg-carimbo" aria-label="não lido" />
                </span>
                <span class="mt-0.5 block text-xs text-tinta-suave">{{ n.mensagem }}</span>
                <span class="mt-1 block text-[0.65rem] uppercase tracking-wider text-tinta-apagada">{{ quando(n.criadaEm) }}</span>
              </span>
            </button>
          </li>
        </ul>
      </div>
    </Transition>
  </div>
</template>
