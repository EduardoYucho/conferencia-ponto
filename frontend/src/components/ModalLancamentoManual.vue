<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { usePontoStore } from '@/stores/ponto'
import { mensagemDe } from '@/utils/erros'
import { diaMes, saldoComSentido } from '@/utils/horas'
import { dataISO, deISO, diaSemanaCurto, paraMinutos } from '@/utils/tempo'
import Icone from '@/components/Icone.vue'

/**
 * "Lançar horas trabalhadas": para um dia sem expediente ou feriado em que a pessoa trabalhou. Nesses dias não
 * há horário previsto, então as horas contam inteiras a favor. Quem aceita o lançamento é o servidor (ele sabe
 * se o dia é feriado); aqui só se ajuda a digitar.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** Data sugerida (yyyy-MM-dd). Sem ela, sugere o último dia sem expediente do horário da pessoa. */
  dataInicial: { type: String, default: null },
})
const emit = defineEmits(['update:modelValue', 'salvo'])

const store = usePontoStore()

/** Quantos períodos cabem num dia (vem do servidor; o número é só a reserva). */
const maximo = computed(() => store.limites?.intervalosPorDia ?? 3)
const data = ref('')
const intervalos = ref([])
const erroServidor = ref('')
const errosCampo = ref({})
const tentouEnviar = ref(false)
const campoData = ref(null)
let sequencia = 0
let quemAbriu = null

const novoIntervalo = (entrada = '') => ({ id: ++sequencia, entrada, saida: '' })

/** O dia sem expediente mais recente (até hoje) no horário da pessoa; sem nenhum nas últimas semanas, hoje. */
function ultimoDiaSemExpediente(hojeIso) {
  const d = deISO(hojeIso)
  for (let i = 0; i < 14; i++) {
    const iso = dataISO(d)
    if (!store.temExpediente(iso)) return iso
    d.setDate(d.getDate() - 1)
  }
  return hojeIso
}

function reiniciar() {
  data.value = props.dataInicial ?? ultimoDiaSemExpediente(store.hoje)
  intervalos.value = [novoIntervalo()]
  erroServidor.value = ''
  errosCampo.value = {}
  tentouEnviar.value = false
}

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) {
      quemAbriu?.focus?.()
      quemAbriu = null
      return
    }
    quemAbriu = document.activeElement
    reiniciar()
    await nextTick()
    // com a data já escolhida (veio do dia), o primeiro campo a preencher é a entrada
    if (props.dataInicial) document.getElementById(`lanc-entrada-${intervalos.value[0].id}`)?.focus()
    else campoData.value?.focus()
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

async function adicionarIntervalo() {
  if (intervalos.value.length >= maximo.value) return
  // o período seguinte costuma começar onde o anterior terminou
  intervalos.value.push(novoIntervalo(intervalos.value.at(-1)?.saida ?? ''))
  await nextTick()
  document.getElementById(`lanc-entrada-${intervalos.value.at(-1).id}`)?.focus()
}

function removerIntervalo(indice) {
  intervalos.value.splice(indice, 1)
}

// ------------------------------------------------------------- ajuda de digitação (quem decide é o servidor)
/** O horário da pessoa não prevê trabalho neste dia (sábado e domingo, no horário mais comum). */
const semExpediente = computed(() => (data.value ? !store.temExpediente(data.value) : false))

const errosIntervalos = computed(() =>
  intervalos.value.map((iv, i) => {
    if (!iv.entrada || !iv.saida) return 'Informe a entrada e a saída.'
    const e = paraMinutos(iv.entrada)
    const s = paraMinutos(iv.saida)
    if (s <= e) return 'A saída precisa ser depois da entrada.'
    if (i > 0) {
      const saidaAnterior = paraMinutos(intervalos.value[i - 1].saida)
      if (saidaAnterior !== null && e < saidaAnterior) return 'Este período precisa começar depois que o anterior termina.'
    }
    return null
  }),
)

const erroData = computed(() => {
  if (!data.value) return 'Informe a data.'
  if (data.value > store.hoje) return 'Escolha uma data até hoje.'
  return null
})

const valido = computed(() => !erroData.value && errosIntervalos.value.every((e) => e === null))

/** Soma do que foi digitado, só para a pessoa conferir antes de lançar (o saldo do dia vem do servidor). */
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

  let registro
  try {
    registro = await store.postRegistroManual({
      data: data.value,
      intervalos: intervalos.value.map(({ entrada, saida }) => ({ entrada, saida })),
    })
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    errosCampo.value = e?.porCampo ?? {}
    return
  }
  // lançou: a janela fecha antes de avisar a tela (nada depois daqui pode parecer falha e levar a lançar de novo)
  emit('update:modelValue', false)
  emit('salvo', registro)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="janela-fundo" @mousedown.self="fechar">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-lancamento"
        aria-describedby="explicacao-lancamento"
        class="janela"
        novalidate
        @submit.prevent="enviar"
      >
        <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
          <div>
            <h2 id="titulo-lancamento" class="text-xl font-extrabold tracking-tight">Lançar horas trabalhadas</h2>
            <p id="explicacao-lancamento" class="mt-1 text-[0.95rem] text-texto-3">
              Para um dia sem expediente ou feriado em que você trabalhou: as horas contam inteiras a favor.
            </p>
          </div>
          <button
            type="button"
            class="-mt-1 -mr-2 grid size-11 shrink-0 place-items-center rounded-xl text-texto-3 hover:bg-neutro hover:text-texto"
            aria-label="Fechar"
            @click="fechar"
          >
            <Icone nome="fechar" />
          </button>
        </header>

        <div class="flex flex-col gap-5 px-5 py-5">
          <!-- Data -->
          <div>
            <label for="lanc-data" class="rotulo">Dia trabalhado</label>
            <div class="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-2">
              <input
                id="lanc-data"
                ref="campoData"
                v-model="data"
                type="date"
                :max="store.hoje"
                class="campo w-auto min-w-44"
                :aria-invalid="(tentouEnviar && !!erroData) || !!errosCampo.data"
                aria-describedby="lanc-data-ajuda"
              />
              <span v-if="data" class="selo" :class="semExpediente ? 'selo-neutro' : 'selo-atencao'">
                {{ diaSemanaCurto(data) }}, {{ diaMes(data) }} · {{ semExpediente ? 'sem expediente' : 'dia de trabalho' }}
              </span>
            </div>
            <p id="lanc-data-ajuda" class="mt-1.5 text-sm">
              <span v-if="tentouEnviar && erroData" class="font-semibold text-negativo">{{ erroData }}</span>
              <span v-else-if="errosCampo.data" class="font-semibold text-negativo">{{ errosCampo.data }}</span>
              <span v-else-if="data && !semExpediente" class="text-texto-2">
                Neste dia você tem expediente: as horas só são aceitas aqui se o dia for feriado. Para acertar as batidas
                de um dia de trabalho, use "Corrigir os horários".
              </span>
            </p>
          </div>

          <!-- Períodos -->
          <fieldset>
            <legend class="rotulo">Horários em que você trabalhou</legend>
            <ul class="mt-2 flex flex-col gap-2">
              <li v-for="(iv, i) in intervalos" :key="iv.id" class="rounded-xl border border-borda px-3 py-2.5">
                <p v-if="intervalos.length > 1" class="mb-1.5 text-sm font-bold text-texto-2">{{ i + 1 }}º período</p>
                <div class="flex flex-wrap items-end gap-x-3 gap-y-2">
                  <div class="min-w-28 flex-1">
                    <label :for="`lanc-entrada-${iv.id}`" class="rotulo">Entrada</label>
                    <input
                      :id="`lanc-entrada-${iv.id}`"
                      v-model="iv.entrada"
                      type="time"
                      step="60"
                      required
                      class="campo mt-1"
                      :aria-invalid="tentouEnviar && !!errosIntervalos[i]"
                    />
                  </div>
                  <div class="min-w-28 flex-1">
                    <label :for="`lanc-saida-${iv.id}`" class="rotulo">Saída</label>
                    <input
                      :id="`lanc-saida-${iv.id}`"
                      v-model="iv.saida"
                      type="time"
                      step="60"
                      required
                      class="campo mt-1"
                      :aria-invalid="tentouEnviar && !!errosIntervalos[i]"
                    />
                  </div>
                  <button
                    v-if="intervalos.length > 1"
                    type="button"
                    class="botao-linha"
                    :aria-label="`Remover o ${i + 1}º período`"
                    @click="removerIntervalo(i)"
                  ><Icone nome="lixeira" tamanho="18" /> Remover</button>
                </div>
                <p v-if="tentouEnviar && errosIntervalos[i]" class="mt-1.5 text-sm font-semibold text-negativo">{{ errosIntervalos[i] }}</p>
              </li>
            </ul>
            <button
              v-if="intervalos.length < maximo"
              type="button"
              class="botao-secundario mt-3 min-h-11"
              @click="adicionarIntervalo"
            ><Icone nome="somar" tamanho="18" /> Incluir outro período</button>
            <p v-else class="mt-2 text-sm text-texto-3">{{ maximo }} períodos é o máximo por dia.</p>
          </fieldset>

          <p class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 rounded-xl bg-superficie-2 px-4 py-3" aria-live="polite">
            <span class="text-[0.95rem] font-semibold text-texto-2">Horas a lançar</span>
            <span v-if="totalMinutos" class="text-2xl font-extrabold tracking-tight text-positivo">{{ saldoComSentido(totalMinutos * 60) }}</span>
            <span v-else class="text-[0.95rem] text-texto-3">informe a entrada e a saída</span>
          </p>

          <p v-if="erroServidor" class="aviso-erro" role="alert">{{ erroServidor }}</p>
        </div>

        <footer class="sticky bottom-0 flex flex-wrap justify-end gap-2 border-t border-borda bg-superficie px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <button type="button" class="botao-secundario min-h-11" :disabled="store.salvando" @click="fechar">Cancelar</button>
          <button type="submit" class="botao-primario min-h-11 grow sm:grow-0" :disabled="store.salvando || (tentouEnviar && !valido)">
            {{ store.salvando ? 'Lançando…' : 'Lançar as horas' }}
          </button>
        </footer>
      </form>
    </div>
  </Teleport>
</template>
