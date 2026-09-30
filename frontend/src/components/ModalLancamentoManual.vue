<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { usePontoStore } from '@/stores/ponto'
import { dataCurta, dataISO, deISO, diaSemanaCurto, ehFimDeSemana, formatarDuracao, paraMinutos } from '@/utils/tempo'

/**
 * Lançamento manual de intervalos em fim de semana/feriado.
 * Dias não úteis têm jornada base zero: todo o tempo informado vira crédito.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** Data sugerida (yyyy-MM-dd). Sem ela, sugere o último sábado. */
  dataInicial: { type: String, default: null },
})
const emit = defineEmits(['update:modelValue', 'salvo'])

const store = usePontoStore()

const MAX_INTERVALOS = 3
const data = ref('')
const intervalos = ref([])
const erroServidor = ref('')
const errosCampo = ref({})
const tentouEnviar = ref(false)
const campoData = ref(null)

function ultimoSabado(hojeIso) {
  const d = deISO(hojeIso)
  const dia = d.getDay()
  if (dia === 6 || dia === 0) return hojeIso
  d.setDate(d.getDate() - (dia + 1))
  return dataISO(d)
}

function reiniciar() {
  data.value = props.dataInicial ?? ultimoSabado(store.hoje)
  intervalos.value = [{ entrada: '', saida: '' }]
  erroServidor.value = ''
  errosCampo.value = {}
  tentouEnviar.value = false
}

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) return
    reiniciar()
    await nextTick()
    campoData.value?.focus()
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

function adicionarIntervalo() {
  if (intervalos.value.length < MAX_INTERVALOS) {
    const anterior = intervalos.value.at(-1)
    intervalos.value.push({ entrada: anterior?.saida ?? '', saida: '' })
  }
}

function removerIntervalo(indice) {
  intervalos.value.splice(indice, 1)
}

// ------------------------------------------------------------- validação
const fimDeSemana = computed(() => (data.value ? ehFimDeSemana(data.value) : false))

const errosIntervalos = computed(() =>
  intervalos.value.map((iv, i) => {
    if (!iv.entrada || !iv.saida) return 'Informe entrada e saída.'
    const e = paraMinutos(iv.entrada)
    const s = paraMinutos(iv.saida)
    if (s <= e) return 'A saída deve ser posterior à entrada.'
    if (i > 0) {
      const saidaAnterior = paraMinutos(intervalos.value[i - 1].saida)
      if (saidaAnterior !== null && e < saidaAnterior) return 'Deve começar após o fim do 1º intervalo.'
    }
    return null
  }),
)

const erroData = computed(() => {
  if (!data.value) return 'Informe a data.'
  if (data.value > store.hoje) return 'Não é possível lançar em data futura.'
  return null
})

const valido = computed(() => !erroData.value && errosIntervalos.value.every((e) => e === null))

const totalMinutos = computed(() =>
  intervalos.value.reduce((soma, iv) => {
    const e = paraMinutos(iv.entrada)
    const s = paraMinutos(iv.saida)
    return e !== null && s !== null && s > e ? soma + (s - e) : soma
  }, 0),
)

async function enviar() {
  tentouEnviar.value = true
  erroServidor.value = ''
  errosCampo.value = {}
  if (!valido.value) return

  try {
    const registro = await store.postRegistroManual({
      data: data.value,
      intervalos: intervalos.value.map(({ entrada, saida }) => ({ entrada, saida })),
    })
    emit('salvo', registro)
    emit('update:modelValue', false)
  } catch (e) {
    erroServidor.value = e.message
    errosCampo.value = e.porCampo ?? {}
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
          aria-labelledby="titulo-lancamento"
          class="cartao perfurado w-full max-w-lg animate-surgir rounded-b-none sm:rounded-[3px]"
          novalidate
          @submit.prevent="enviar"
        >
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">Lançamento manual</p>
              <h2 id="titulo-lancamento" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">
                Horas em fim de semana / feriado
              </h2>
              <p class="mt-1 text-sm text-tinta-suave">Carga base zero: 100% do tempo informado vira crédito.</p>
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
            <!-- Data -->
            <div>
              <label for="lanc-data" class="rotulo">Data</label>
              <div class="mt-1.5 flex items-center gap-3">
                <input
                  id="lanc-data"
                  ref="campoData"
                  v-model="data"
                  type="date"
                  :max="store.hoje"
                  class="campo max-w-[12rem]"
                  :aria-invalid="tentouEnviar && !!erroData"
                />
                <span
                  v-if="data"
                  class="rounded-[2px] px-2 py-1 text-xs font-semibold uppercase tracking-wide"
                  :class="fimDeSemana ? 'bg-credito/10 text-credito' : 'bg-carimbo/10 text-carimbo'"
                >
                  {{ diaSemanaCurto(data) }} {{ dataCurta(data) }} · {{ fimDeSemana ? 'fim de semana' : 'dia útil' }}
                </span>
              </div>
              <p v-if="tentouEnviar && erroData" class="mt-1.5 text-sm text-carimbo">{{ erroData }}</p>
              <p v-else-if="errosCampo.data" class="mt-1.5 text-sm text-carimbo">{{ errosCampo.data }}</p>
              <p v-else-if="data && !fimDeSemana" class="mt-1.5 text-sm text-tinta-suave">
                Dia útil: o lançamento só será aceito se a data estiver cadastrada como feriado.
              </p>
            </div>

            <!-- Intervalos -->
            <fieldset class="space-y-3">
              <legend class="rotulo">Intervalos trabalhados</legend>
              <div
                v-for="(iv, i) in intervalos"
                :key="i"
                class="rounded-[3px] border border-linha bg-papel/60 p-3"
              >
                <div class="flex items-end gap-3">
                  <span class="carimbo mb-2.5 w-5 shrink-0 text-sm text-tinta-apagada">{{ i + 1 }}º</span>
                  <label class="flex-1">
                    <span class="text-xs text-tinta-suave">Entrada</span>
                    <input v-model="iv.entrada" type="time" step="60" required class="campo mt-1" />
                  </label>
                  <label class="flex-1">
                    <span class="text-xs text-tinta-suave">Saída</span>
                    <input v-model="iv.saida" type="time" step="60" required class="campo mt-1" />
                  </label>
                  <button
                    v-if="intervalos.length > 1"
                    type="button"
                    class="mb-1 rounded-[3px] p-2 text-tinta-suave hover:bg-papel-escuro hover:text-carimbo"
                    :aria-label="`Remover ${i + 1}º intervalo`"
                    @click="removerIntervalo(i)"
                  >
                    <svg viewBox="0 0 20 20" class="size-4" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 10h12" /></svg>
                  </button>
                </div>
                <p v-if="tentouEnviar && errosIntervalos[i]" class="mt-2 pl-8 text-sm text-carimbo">
                  {{ errosIntervalos[i] }}
                </p>
              </div>

              <button
                v-if="intervalos.length < MAX_INTERVALOS"
                type="button"
                class="text-sm font-semibold text-tinta underline decoration-linha decoration-2 underline-offset-4 hover:decoration-tinta"
                @click="adicionarIntervalo"
              >
                + Adicionar 2º intervalo
              </button>
            </fieldset>

            <div class="flex items-center justify-between rounded-[3px] border border-dashed border-tinta/40 px-4 py-3">
              <span class="rotulo">Crédito a lançar</span>
              <span class="carimbo text-2xl font-semibold" :class="totalMinutos ? 'text-credito' : 'text-tinta-apagada'">
                +{{ formatarDuracao(totalMinutos * 60) }}
              </span>
            </div>

            <p v-if="erroServidor" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
              {{ erroServidor }}
            </p>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-dashed border-linha px-5 py-4 sm:flex-row sm:justify-end">
            <button type="button" class="botao-secundario" :disabled="store.salvando" @click="fechar">Cancelar</button>
            <button type="submit" class="botao-primario" :disabled="store.salvando || (tentouEnviar && !valido)">
              {{ store.salvando ? 'Lançando…' : 'Lançar horas' }}
            </button>
          </footer>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>
