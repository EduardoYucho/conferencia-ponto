<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { usePontoStore } from '@/stores/ponto'
import {
  dataBR, dataCurta, diaSemanaCurto, duracaoEmSegundos, formatarDuracao, formatarSaldo, normalizarDuracao,
} from '@/utils/tempo'

/**
 * Lançamento avulso no banco de horas: abate horas (compensação com folga ou saída antecipada, horas pagas
 * pela empresa) ou credita (correção do RH). Não mexe nas batidas do dia: entra no saldo do mês e do ciclo.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** Data sugerida (yyyy-MM-dd); sem ela, hoje. */
  dataInicial: { type: String, default: null },
  /** Pré-preenchimento (ex.: folga compensada = um dia inteiro do horário). */
  duracaoInicial: { type: String, default: '' },
  descricaoInicial: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'salvo'])

const store = usePontoStore()

const SUGESTOES = ['Compensação de horas', 'Folga compensada', 'Saída antecipada compensada', 'Horas pagas pela empresa', 'Correção do RH']

const data = ref('')
const sentido = ref('DEBITO')
const duracaoTexto = ref('')
const descricao = ref('')
const tentou = ref(false)
const erroServidor = ref('')
const campoDuracao = ref(null)

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) return
    data.value = props.dataInicial ?? store.hoje
    sentido.value = 'DEBITO'
    duracaoTexto.value = props.duracaoInicial
    descricao.value = props.descricaoInicial
    tentou.value = false
    erroServidor.value = ''
    await nextTick()
    campoDuracao.value?.focus()
  },
  { immediate: true },
)

function fechar() {
  if (!store.salvando) emit('update:modelValue', false)
}
function aoTeclar(evento) {
  if (evento.key === 'Escape' && props.modelValue) fechar()
}
window.addEventListener('keydown', aoTeclar)
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclar))

const inicioCiclo = computed(() => store.ciclo?.dataInicio ?? null)
const duracao = computed(() => normalizarDuracao(duracaoTexto.value))
const segundos = computed(() => (duracao.value ? duracaoEmSegundos(duracao.value) * (sentido.value === 'DEBITO' ? -1 : 1) : 0))

const erros = computed(() => ({
  data: !data.value
    ? 'Informe a data.'
    : inicioCiclo.value && data.value < inicioCiclo.value
      ? `O ciclo atual do banco começou em ${dataBR(inicioCiclo.value)}: lance a partir dessa data.`
      : null,
  duracao: !duracaoTexto.value.trim()
    ? 'Informe as horas (ex.: 04:00).'
    : !duracao.value ? 'Use horas e minutos, ex.: 04:00, 8:48 ou 4h30.' : null,
  descricao: !descricao.value.trim() ? 'Informe o motivo.' : null,
}))
const valido = computed(() => Object.values(erros.value).every((e) => !e))

/** Saldo do ciclo depois do lançamento (se a data estiver no ciclo aberto). */
const saldoDepois = computed(() => {
  const c = store.ciclo
  if (!c || !segundos.value || (inicioCiclo.value && data.value < inicioCiclo.value)) return null
  return c.saldoSegundos + segundos.value
})

function preencher(hhmm) {
  duracaoTexto.value = hhmm
}

/** "Dia inteiro" e "meio dia" pelo horário da pessoa no dia escolhido (ou o dia típico do horário). */
const diaInteiro = computed(() => {
  const carga = (data.value && store.cargaDoDia(data.value)) || store.cargaDiaInteiro
  return formatarDuracao(carga, { curto: true })
})
const meioDia = computed(() => formatarDuracao(Math.round(duracaoEmSegundos(diaInteiro.value) / 2 / 60) * 60, { curto: true }))

async function enviar() {
  tentou.value = true
  erroServidor.value = ''
  if (!valido.value) return
  try {
    const salvo = await store.lancarNoBanco({
      data: data.value,
      duracao: duracao.value,
      sentido: sentido.value,
      descricao: descricao.value.trim(),
    })
    emit('salvo', salvo)
    emit('update:modelValue', false)
  } catch (e) {
    erroServidor.value = e.message
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      leave-active-class="transition duration-150 ease-in"
      leave-to-class="opacity-0"
    >
      <div
        v-if="modelValue"
        class="fixed inset-0 z-50 flex items-end justify-center bg-tinta/45 p-0 backdrop-blur-[2px] sm:items-center sm:p-6"
        @mousedown.self="fechar"
      >
        <form
          role="dialog"
          aria-modal="true"
          aria-labelledby="titulo-lanc-banco"
          class="cartao perfurado w-full max-w-lg animate-surgir rounded-b-none sm:rounded-[3px]"
          novalidate
          @submit.prevent="enviar"
        >
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">Banco de horas</p>
              <h2 id="titulo-lanc-banco" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">
                Lançar horas no banco
              </h2>
              <p class="mt-1 text-sm text-tinta-suave">
                Abata as horas a mais (ex.: folga ou saída antecipada compensada) ou credite uma correção.
                As batidas do dia não mudam: o lançamento entra no saldo do mês e do ciclo.
              </p>
            </div>
            <button
              type="button"
              class="-mr-1 rounded-[3px] p-1.5 text-tinta-suave hover:bg-papel-escuro hover:text-tinta"
              aria-label="Fechar"
              @click="fechar"
            >
              <svg viewBox="0 0 20 20" class="size-5" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M5 5l10 10M15 5L5 15" /></svg>
            </button>
          </header>

          <div class="space-y-5 px-5 py-5">
            <!-- Sentido -->
            <fieldset>
              <legend class="rotulo">Tipo</legend>
              <div class="mt-1.5 grid grid-cols-2 gap-2" role="radiogroup">
                <label
                  class="flex cursor-pointer items-center gap-2 rounded-[3px] border px-3 py-2 text-sm transition"
                  :class="sentido === 'DEBITO' ? 'border-carimbo bg-carimbo/10 font-semibold text-carimbo' : 'border-linha hover:border-tinta'"
                >
                  <input v-model="sentido" type="radio" value="DEBITO" class="sr-only" />
                  <span class="carimbo text-lg leading-none">−</span> Abater do banco
                </label>
                <label
                  class="flex cursor-pointer items-center gap-2 rounded-[3px] border px-3 py-2 text-sm transition"
                  :class="sentido === 'CREDITO' ? 'border-credito bg-credito/10 font-semibold text-credito' : 'border-linha hover:border-tinta'"
                >
                  <input v-model="sentido" type="radio" value="CREDITO" class="sr-only" />
                  <span class="carimbo text-lg leading-none">+</span> Creditar
                </label>
              </div>
            </fieldset>

            <div class="grid gap-4 sm:grid-cols-2">
              <label class="flex flex-col">
                <span class="rotulo">Data</span>
                <input v-model="data" type="date" :min="inicioCiclo ?? undefined" class="campo mt-1.5" :aria-invalid="tentou && !!erros.data" />
                <span v-if="data" class="mt-1 text-xs text-tinta-suave">{{ diaSemanaCurto(data) }} {{ dataCurta(data) }}</span>
              </label>
              <label class="flex flex-col">
                <span class="rotulo">Horas</span>
                <input
                  ref="campoDuracao"
                  v-model="duracaoTexto"
                  type="text"
                  inputmode="numeric"
                  placeholder="04:00"
                  class="campo carimbo mt-1.5"
                  :aria-invalid="tentou && !!erros.duracao"
                />
                <span class="mt-1 flex gap-2 text-xs">
                  <button type="button" class="text-tinta-suave underline underline-offset-4 hover:text-tinta" @click="preencher(diaInteiro)">dia inteiro ({{ diaInteiro }})</button>
                  <button type="button" class="text-tinta-suave underline underline-offset-4 hover:text-tinta" @click="preencher(meioDia)">meio dia</button>
                </span>
              </label>
            </div>
            <p v-if="tentou && (erros.data || erros.duracao)" class="-mt-3 text-sm text-carimbo">{{ erros.data || erros.duracao }}</p>

            <div>
              <label for="lanc-banco-motivo" class="rotulo">Motivo</label>
              <input
                id="lanc-banco-motivo"
                v-model="descricao"
                type="text"
                maxlength="200"
                class="campo mt-1.5 font-sans"
                placeholder="Ex.: Compensação de horas"
                :aria-invalid="tentou && !!erros.descricao"
              />
              <div class="mt-2 flex flex-wrap gap-1.5">
                <button
                  v-for="s in SUGESTOES"
                  :key="s"
                  type="button"
                  class="rounded-full border border-linha px-2.5 py-0.5 text-xs text-tinta-suave transition hover:border-tinta hover:text-tinta"
                  @click="descricao = s"
                >{{ s }}</button>
              </div>
              <p v-if="tentou && erros.descricao" class="mt-1.5 text-sm text-carimbo">{{ erros.descricao }}</p>
            </div>

            <div class="flex items-center justify-between rounded-[3px] border border-dashed border-tinta/40 px-4 py-3">
              <span class="rotulo">{{ sentido === 'DEBITO' ? 'Abater' : 'Creditar' }}</span>
              <span class="text-right">
                <span class="carimbo block text-2xl font-semibold" :class="!segundos ? 'text-tinta-apagada' : segundos < 0 ? 'text-carimbo' : 'text-credito'">
                  {{ segundos ? formatarSaldo(segundos) : `${sentido === 'DEBITO' ? '−' : '+'}${formatarDuracao(0)}` }}
                </span>
                <span v-if="saldoDepois !== null" class="text-xs text-tinta-suave">
                  ciclo: {{ formatarSaldo(store.ciclo.saldoSegundos) }} → <b class="carimbo">{{ formatarSaldo(saldoDepois) }}</b>
                </span>
              </span>
            </div>

            <p v-if="erroServidor" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
              {{ erroServidor }}
            </p>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-dashed border-linha px-5 py-4 sm:flex-row sm:justify-end">
            <button type="button" class="botao-secundario" :disabled="store.salvando" @click="fechar">Cancelar</button>
            <button type="submit" class="botao-primario" :disabled="store.salvando || (tentou && !valido)">
              {{ store.salvando ? 'Lançando…' : sentido === 'DEBITO' ? 'Abater do banco' : 'Creditar no banco' }}
            </button>
          </footer>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>
