<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'

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

const icone = (tipo) => (tipo === 'CONCILIACAO' ? 'comparar' : tipo === 'CICLO_VENCIDO' ? 'alerta' : 'relogio')
</script>

<template>
  <div ref="raiz" class="relative">
    <button
      type="button"
      class="relative grid size-11 place-items-center rounded-xl text-texto-3 transition hover:bg-neutro hover:text-texto"
      :class="{ 'animate-pulse text-negativo': pulsando }"
      :aria-label="naoLidas ? `Avisos: ${naoLidas} não lido(s)` : 'Avisos'"
      :aria-expanded="aberto"
      @click="aberto = !aberto"
    >
      <Icone nome="sino" tamanho="22" />
      <span
        v-if="naoLidas"
        class="absolute top-1 right-1 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-negativo-solido px-1 text-[0.68rem] font-bold text-white"
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
        class="cartao absolute right-0 z-50 mt-2 w-[min(24rem,calc(100vw-2rem))] overflow-hidden shadow-xl"
        role="dialog"
        aria-label="Avisos"
      >
        <div class="flex items-center justify-between gap-3 border-b border-borda px-4 py-3">
          <p class="text-base font-extrabold">Avisos</p>
          <button v-if="naoLidas" type="button" class="link text-sm" @click="marcarTodas">Marcar todos como lidos</button>
        </div>
        <p v-if="falhaAoMarcar" role="alert" class="border-b border-borda px-4 py-2 text-sm text-negativo">{{ falhaAoMarcar }}</p>
        <div v-if="falhouCarga" class="px-4 py-3">
          <EstadoDaTela :erro="erroCarga" :carregando="carregando" @tentar="carregar" />
        </div>
        <ul class="max-h-96 divide-y divide-borda overflow-y-auto">
          <li v-if="!lista.length && !falhouCarga" class="px-4 py-8 text-center text-[0.95rem] text-texto-3">
            {{ carregado ? 'Nenhum aviso por enquanto.' : 'Carregando…' }}
          </li>
          <li v-for="n in lista" :key="n.id">
            <button
              type="button"
              class="flex w-full gap-3 px-4 py-3 text-left transition hover:bg-neutro"
              @click="abrirNotificacao(n)"
            >
              <span
                class="mt-0.5 grid size-8 shrink-0 place-items-center rounded-full"
                :class="n.tipo === 'CONCILIACAO' ? 'bg-primaria-suave text-primaria' : n.tipo === 'CICLO_VENCIDO' ? 'bg-negativo-suave text-negativo' : 'bg-atencao-suave text-atencao'"
                aria-hidden="true"
              ><Icone :nome="icone(n.tipo)" tamanho="17" /></span>
              <span class="min-w-0 flex-1">
                <span class="flex items-baseline gap-2">
                  <span class="text-[0.95rem]" :class="n.lida ? 'font-semibold text-texto-2' : 'font-bold'">{{ n.titulo }}</span>
                  <span v-if="!n.lida" class="size-2 shrink-0 rounded-full bg-primaria" aria-label="não lido" />
                </span>
                <span class="mt-0.5 block text-sm text-texto-3">{{ n.mensagem }}</span>
                <span class="mt-1 block text-[0.8rem] text-texto-4">{{ quando(n.criadaEm) }}</span>
              </span>
            </button>
          </li>
        </ul>
      </div>
    </Transition>
  </div>
</template>
