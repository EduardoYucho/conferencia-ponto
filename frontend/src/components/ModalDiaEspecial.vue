<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { mensagemDe } from '@/utils/erros'
import { dataBR, dataCurta, diaSemanaCurto } from '@/utils/tempo'

/**
 * Marca um dia (ou período) sem jornada: feriado, folga, férias, atestado, licença ou outra justificativa
 * (abono). Esses dias têm jornada base zero: não geram débito. Se o dia já estiver marcado, mostra a marcação
 * e permite removê-la.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** yyyy-MM-dd */
  data: { type: String, default: null },
  /** Marcação atual do dia (store.marcadoresDoMes[data]), se houver. */
  marcador: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'salvo', 'compensar'])

const store = usePontoStore()
const auth = useAuthStore()

/** Feriado vale para todos: só o administrador cadastra e remove. */
const TODOS_TIPOS = [
  { valor: 'FERIADO', rotulo: 'Feriado' },
  { valor: 'FOLGA', rotulo: 'Folga' },
  { valor: 'FERIAS', rotulo: 'Férias' },
  { valor: 'ATESTADO', rotulo: 'Atestado' },
  { valor: 'LICENCA', rotulo: 'Licença' },
  { valor: 'ABONO', rotulo: 'Outra justificativa' },
]
const TIPOS = computed(() => TODOS_TIPOS.filter((t) => t.valor !== 'FERIADO' || auth.ehAdmin))
const ABRANGENCIAS = [
  { valor: 'MUNICIPAL', rotulo: 'Municipal' },
  { valor: 'ESTADUAL', rotulo: 'Estadual' },
  { valor: 'NACIONAL', rotulo: 'Nacional' },
  { valor: 'EMPRESA', rotulo: 'Empresa / ponto facultativo' },
]
const PLACEHOLDER = {
  FERIADO: 'Ex.: Corpus Christi',
  FOLGA: 'Ex.: folga de aniversário (opcional)',
  FERIAS: 'Ex.: férias 2026 (opcional)',
  ATESTADO: 'Opcional',
  LICENCA: 'Opcional',
  ABONO: 'Ex.: doação de sangue, declaração de comparecimento',
}

const tipo = ref('FERIADO')
const dataInicio = ref('')
const dataFim = ref('')
const descricao = ref('')
const abrangencia = ref('MUNICIPAL')
const tentou = ref(false)
const erroServidor = ref('')
const confirmandoRemocao = ref(false)
const campoDescricao = ref(null)

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) return
    tipo.value = auth.ehAdmin ? 'FERIADO' : 'FOLGA'
    dataInicio.value = props.data ?? store.hoje
    dataFim.value = props.data ?? store.hoje
    descricao.value = ''
    abrangencia.value = 'MUNICIPAL'
    tentou.value = false
    erroServidor.value = ''
    confirmandoRemocao.value = false
    await nextTick()
    campoDescricao.value?.focus()
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

const ehFeriado = computed(() => tipo.value === 'FERIADO')
const descricaoObrigatoria = computed(() => tipo.value === 'FERIADO' || tipo.value === 'ABONO')

function aoMudarInicio() {
  if (!dataFim.value || dataFim.value < dataInicio.value) dataFim.value = dataInicio.value
}

const erro = computed(() => {
  if (!dataInicio.value) return 'Informe a data.'
  if (!ehFeriado.value && dataFim.value && dataFim.value < dataInicio.value) return 'O fim deve ser igual ou posterior ao início.'
  if (descricaoObrigatoria.value && !descricao.value.trim()) {
    return ehFeriado.value ? 'Informe o nome do feriado.' : 'Informe a justificativa.'
  }
  return null
})

const dias = computed(() => {
  if (ehFeriado.value || !dataInicio.value || !dataFim.value || dataFim.value < dataInicio.value) return 1
  return Math.round((new Date(dataFim.value) - new Date(dataInicio.value)) / 86_400_000) + 1
})

/** Período da ausência que cobre o dia (para mostrar e remover). */
const periodoAtual = computed(() => {
  const a = props.marcador?.ausencia
  if (!a) return null
  return a.dataInicio === a.dataFim ? dataBR(a.dataInicio) : `${dataBR(a.dataInicio)} a ${dataBR(a.dataFim)}`
})

async function enviar() {
  tentou.value = true
  erroServidor.value = ''
  if (erro.value) return
  try {
    await store.marcarDias({
      tipo: tipo.value,
      dataInicio: dataInicio.value,
      dataFim: ehFeriado.value ? dataInicio.value : dataFim.value,
      descricao: descricao.value.trim(),
      abrangencia: ehFeriado.value ? abrangencia.value : undefined,
    })
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    return
  }
  // marcou: a janela fecha antes de avisar a tela (nada depois daqui pode parecer falha da gravação)
  emit('update:modelValue', false)
  const rotulo = TODOS_TIPOS.find((t) => t.valor === tipo.value)?.rotulo ?? 'Marcação'
  emit('salvo', ehFeriado.value || dias.value === 1
    ? `${rotulo} em ${dataBR(dataInicio.value)}: o dia não gera débito`
    : `${rotulo} de ${dataBR(dataInicio.value)} a ${dataBR(dataFim.value)}: ${dias.value} dias sem débito`)
}

async function remover() {
  if (!confirmandoRemocao.value) {
    confirmandoRemocao.value = true
    return
  }
  erroServidor.value = ''
  // a marcação some da tela assim que o mês recarrega: guarda o texto antes
  const marcador = props.marcador
  const texto = marcador.tipo === 'feriado'
    ? `Feriado de ${dataBR(marcador.feriado.data)} removido: o dia voltou a ser útil`
    : `${marcador.rotulo} (${periodoAtual.value}) removida: os dias voltaram a ser úteis`
  try {
    await store.removerMarcacao(marcador)
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    confirmandoRemocao.value = false
    return
  }
  emit('update:modelValue', false)
  emit('salvo', texto)
}

function compensar() {
  emit('compensar', dataInicio.value || props.data)
  emit('update:modelValue', false)
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
          aria-labelledby="titulo-dia-especial"
          class="cartao perfurado w-full max-w-lg animate-surgir rounded-b-none sm:rounded-[3px]"
          novalidate
          @submit.prevent="enviar"
        >
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">{{ data ? `${diaSemanaCurto(data)} ${dataCurta(data)}` : 'Calendário' }}</p>
              <h2 id="titulo-dia-especial" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">
                Folga, feriado ou justificativa
              </h2>
              <p class="mt-1 text-sm text-tinta-suave">
                O dia passa a ter jornada base zero: não gera débito e sai das pendências. Se trabalhar nele, o tempo vira crédito.
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

          <!-- Dia já marcado -->
          <div v-if="marcador" class="space-y-4 px-5 py-5">
            <div class="rounded-[3px] border border-linha bg-papel/60 px-4 py-3">
              <p class="rotulo">Marcado como</p>
              <p class="mt-1 font-semibold">
                {{ marcador.tipo === 'feriado' ? `Feriado · ${marcador.rotulo}` : marcador.rotulo }}
                <span v-if="marcador.descricao" class="font-normal text-tinta-suave"> · {{ marcador.descricao }}</span>
              </p>
              <p v-if="marcador.tipo === 'feriado'" class="text-sm text-tinta-suave">{{ marcador.feriado?.abrangenciaRotulo }}</p>
              <p v-else-if="periodoAtual" class="text-sm text-tinta-suave">Período: {{ periodoAtual }}</p>
            </div>
            <p v-if="marcador.tipo === 'feriado' && !auth.ehAdmin" class="text-sm text-tinta-suave">
              Feriados valem para todos: só o administrador remove.
            </p>
            <p v-else class="text-sm text-tinta-suave">
              Para trocar a marcação, remova esta primeiro{{ marcador.tipo === 'ausencia' && marcador.ausencia?.dataInicio !== marcador.ausencia?.dataFim ? ' (o período inteiro é removido)' : '' }}.
            </p>
            <p v-if="erroServidor" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
              {{ erroServidor }}
            </p>
            <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button type="button" class="botao-secundario" :disabled="store.salvando" @click="fechar">Fechar</button>
              <button
                v-if="marcador.tipo !== 'feriado' || auth.ehAdmin"
                type="button"
                class="botao"
                :class="confirmandoRemocao ? 'bg-carimbo text-cartao' : 'border border-carimbo/50 text-carimbo hover:bg-carimbo/10'"
                :disabled="store.salvando"
                @click="remover"
              >{{ confirmandoRemocao ? 'Confirmar remoção?' : 'Remover marcação' }}</button>
            </div>
          </div>

          <!-- Nova marcação -->
          <template v-else>
            <div class="space-y-5 px-5 py-5">
              <fieldset>
                <legend class="rotulo">Tipo</legend>
                <div class="mt-1.5 grid grid-cols-2 gap-2 sm:grid-cols-3" role="radiogroup">
                  <label
                    v-for="t in TIPOS"
                    :key="t.valor"
                    class="cursor-pointer rounded-[3px] border px-3 py-2 text-center text-sm transition"
                    :class="tipo === t.valor ? 'border-tinta bg-tinta text-cartao font-semibold' : 'border-linha hover:border-tinta'"
                  >
                    <input v-model="tipo" type="radio" :value="t.valor" class="sr-only" />
                    {{ t.rotulo }}
                  </label>
                </div>
              </fieldset>

              <div class="grid gap-4 sm:grid-cols-2">
                <label class="flex flex-col">
                  <span class="rotulo">{{ ehFeriado ? 'Data' : 'De' }}</span>
                  <input v-model="dataInicio" type="date" class="campo mt-1.5" @change="aoMudarInicio" />
                </label>
                <label v-if="!ehFeriado" class="flex flex-col">
                  <span class="rotulo">Até</span>
                  <input v-model="dataFim" type="date" :min="dataInicio || undefined" class="campo mt-1.5" />
                </label>
                <label v-else class="flex flex-col">
                  <span class="rotulo">Abrangência</span>
                  <select v-model="abrangencia" class="campo mt-1.5 font-sans">
                    <option v-for="a in ABRANGENCIAS" :key="a.valor" :value="a.valor">{{ a.rotulo }}</option>
                  </select>
                </label>
              </div>

              <label class="flex flex-col">
                <span class="rotulo">{{ ehFeriado ? 'Nome do feriado' : tipo === 'ABONO' ? 'Justificativa' : 'Descrição' }}</span>
                <input
                  ref="campoDescricao"
                  v-model="descricao"
                  type="text"
                  :maxlength="ehFeriado ? 120 : 200"
                  class="campo mt-1.5 font-sans"
                  :placeholder="PLACEHOLDER[tipo]"
                />
              </label>

              <p v-if="tentou && erro" class="text-sm text-carimbo">{{ erro }}</p>
              <p v-if="erroServidor" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
                {{ erroServidor }}
              </p>

              <p class="rounded-[3px] border border-dashed border-linha px-3 py-2 text-xs text-tinta-suave">
                Folga para <b>descontar do banco</b> (compensar horas a mais)?
                <button type="button" class="font-semibold text-tinta underline underline-offset-4" @click="compensar">Lançar no banco</button>
                em vez de marcar o dia.
              </p>
            </div>

            <footer class="flex flex-col-reverse gap-2 border-t border-dashed border-linha px-5 py-4 sm:flex-row sm:justify-end">
              <button type="button" class="botao-secundario" :disabled="store.salvando" @click="fechar">Cancelar</button>
              <button type="submit" class="botao-primario" :disabled="store.salvando || (tentou && !!erro)">
                {{ store.salvando ? 'Salvando…' : dias > 1 ? `Marcar ${dias} dias` : 'Marcar dia' }}
              </button>
            </footer>
          </template>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>
