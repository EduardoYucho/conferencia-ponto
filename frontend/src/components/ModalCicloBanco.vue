<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { usePontoStore } from '@/stores/ponto'
import { mensagemDe } from '@/utils/erros'
import { dataBR, deISO, dataISO, formatarSaldo } from '@/utils/tempo'

/**
 * Banco de horas semestral.
 *  - modo "fechar": o botão que sinaliza o fechamento das horas. Congela o saldo exato até o
 *    último dia escolhido e recomeça a contagem do zero no dia seguinte (dá para desfazer).
 *  - modo "periodo": corrige o início (e a previsão de término) do ciclo aberto.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 'fechar' | 'periodo' */
  modo: { type: String, default: 'fechar' },
  /** CicloBancoResponse do ciclo aberto */
  ciclo: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'concluido'])

const store = usePontoStore()

const ultimoDia = ref('')
const observacao = ref('')
const inicio = ref('')
const fimPrevisto = ref('')
const enviando = ref(false)
const erro = ref('')
const resultado = ref(null) // { fechado, novo } após fechar
const historico = ref([])
const campoInicial = ref(null)

async function reiniciar() {
  erro.value = ''
  resultado.value = null
  observacao.value = ''
  ultimoDia.value = props.ciclo?.sugestaoFechamento ?? store.hoje
  inicio.value = props.ciclo?.dataInicio ?? ''
  fimPrevisto.value = props.ciclo?.dataFimPrevista ?? ''
  historico.value = []
  try {
    historico.value = (await pontoApi.ciclos()).filter((c) => c.status === 'FECHADO')
  } catch {
    // histórico é só informativo
  }
}

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) return
    await reiniciar()
    await nextTick()
    campoInicial.value?.focus()
  },
  { immediate: true },
)

function fechar() {
  if (!enviando.value) emit('update:modelValue', false)
}

function aoTeclar(evento) {
  if (evento.key === 'Escape' && props.modelValue) fechar()
}
window.addEventListener('keydown', aoTeclar)
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclar))

const proximoDia = (iso) => {
  const d = deISO(iso)
  d.setDate(d.getDate() + 1)
  return dataISO(d)
}

/** Previsão padrão: início + 6 meses − 1 dia. */
function previsaoPara(iso) {
  const d = deISO(iso)
  d.setMonth(d.getMonth() + 6)
  d.setDate(d.getDate() - 1)
  return dataISO(d)
}

const erroFechamento = computed(() => {
  if (!ultimoDia.value) return 'Informe o último dia que entra neste fechamento.'
  if (props.ciclo && ultimoDia.value < props.ciclo.dataInicio) return `O ciclo começou em ${dataBR(props.ciclo.dataInicio)}.`
  if (ultimoDia.value > store.hoje) return 'O fechamento não pode ser depois de hoje.'
  return null
})

const erroPeriodo = computed(() => {
  if (!inicio.value) return 'Informe o início do ciclo.'
  if (fimPrevisto.value && fimPrevisto.value <= inicio.value) return 'A previsão de término deve ser depois do início.'
  return null
})

/**
 * Grava no servidor e atualiza os saldos da tela. Só a recusa do servidor vira erro aqui: se a gravação deu
 * certo e os saldos não puderam ser atualizados, o aviso geral da tela oferece "Atualizar agora".
 * @returns {Promise<{ ok: boolean, dados?: any }>}
 */
async function gravar(chamada) {
  enviando.value = true
  erro.value = ''
  try {
    let dados
    try {
      dados = await chamada()
    } catch (e) {
      erro.value = mensagemDe(e)
      return { ok: false }
    }
    await store.atualizarSaldos()
    return { ok: true, dados }
  } finally {
    enviando.value = false
  }
}

async function confirmarFechamento() {
  if (erroFechamento.value) return
  const fechamento = await gravar(() =>
    pontoApi.fecharCiclo({ ultimoDia: ultimoDia.value, observacao: observacao.value.trim() || null }))
  if (!fechamento.ok) return
  resultado.value = fechamento.dados
  emit('concluido', resultado.value)
}

async function desfazer() {
  const desfeito = await gravar(() => pontoApi.desfazerFechamento())
  if (!desfeito.ok) return
  resultado.value = null
  emit('update:modelValue', false)
  emit('concluido', null)
}

async function salvarPeriodo() {
  if (erroPeriodo.value) return
  const salvo = await gravar(() => pontoApi.corrigirCiclo({ dataInicio: inicio.value, dataFimPrevista: fimPrevisto.value || null }))
  if (!salvo.ok) return
  emit('update:modelValue', false)
  emit('concluido', null)
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
        v-if="modelValue && ciclo"
        class="fixed inset-0 z-50 flex items-end justify-center bg-tinta/45 p-0 backdrop-blur-[2px] sm:items-center sm:p-6"
        @mousedown.self="fechar"
      >
        <form
          role="dialog"
          aria-modal="true"
          aria-labelledby="titulo-ciclo"
          class="cartao perfurado flex max-h-[94vh] w-full max-w-lg animate-surgir flex-col rounded-b-none sm:rounded-[3px]"
          novalidate
          @submit.prevent="modo === 'fechar' ? confirmarFechamento() : salvarPeriodo()"
        >
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">Banco de horas · ciclo desde {{ dataBR(ciclo.dataInicio) }}</p>
              <h2 id="titulo-ciclo" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">
                {{ modo === 'fechar' ? (resultado ? 'Banco de horas fechado' : 'Fechar o banco de horas') : 'Período do ciclo atual' }}
              </h2>
              <p v-if="modo === 'fechar' && !resultado" class="mt-1 text-sm text-tinta-suave">
                Use quando o RH zerar o banco. O saldo exato fica guardado e a contagem recomeça do zero.
              </p>
            </div>
            <button type="button" class="-mr-1 rounded-[3px] p-1.5 text-tinta-suave hover:bg-papel-escuro hover:text-tinta" aria-label="Fechar" @click="fechar">
              <svg viewBox="0 0 20 20" class="size-5" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M5 5l10 10M15 5L5 15" /></svg>
            </button>
          </header>

          <div class="space-y-5 overflow-y-auto px-5 py-5">
            <!-- Resultado do fechamento -->
            <template v-if="modo === 'fechar' && resultado">
              <div class="rounded-[3px] border border-credito/40 bg-credito/5 px-4 py-3">
                <p class="text-sm">
                  Ciclo de <b>{{ dataBR(resultado.fechado.dataInicio) }}</b> a <b>{{ dataBR(resultado.fechado.dataFim) }}</b> fechado com
                </p>
                <p class="carimbo mt-1 text-3xl font-semibold" :class="resultado.fechado.saldoSegundos < 0 ? 'text-carimbo' : 'text-credito'">
                  {{ formatarSaldo(resultado.fechado.saldoSegundos) }}
                </p>
                <p class="mt-2 text-sm text-tinta-suave">
                  Novo ciclo desde <b class="text-tinta">{{ dataBR(resultado.novo.dataInicio) }}</b>
                  (fechamento previsto em {{ dataBR(resultado.novo.dataFimPrevista) }}), saldo atual
                  <b class="carimbo text-tinta">{{ formatarSaldo(resultado.novo.saldoSegundos) }}</b>.
                </p>
              </div>
              <p class="text-xs text-tinta-suave">Clicou por engano? Dá para desfazer enquanto este for o último fechamento.</p>
            </template>

            <!-- Fechar -->
            <template v-else-if="modo === 'fechar'">
              <div class="flex items-baseline justify-between gap-3 rounded-[3px] border border-dashed border-tinta/40 px-4 py-3">
                <span class="text-sm text-tinta-suave">Saldo do ciclo hoje</span>
                <span class="carimbo text-2xl font-semibold" :class="ciclo.saldoSegundos < 0 ? 'text-carimbo' : ciclo.saldoSegundos > 0 ? 'text-credito' : ''">
                  {{ formatarSaldo(ciclo.saldoSegundos) }}
                </span>
              </div>
              <div>
                <label for="ciclo-ultimo-dia" class="rotulo">Último dia deste ciclo</label>
                <input
                  id="ciclo-ultimo-dia"
                  ref="campoInicial"
                  v-model="ultimoDia"
                  type="date"
                  class="campo mt-1.5"
                  :min="ciclo.dataInicio"
                  :max="store.hoje"
                  :aria-invalid="!!erroFechamento"
                />
                <p class="mt-1.5 text-xs text-tinta-suave">
                  O saldo é apurado até este dia (inclusive). O novo ciclo começa em
                  <b>{{ ultimoDia ? dataBR(proximoDia(ultimoDia)) : '—' }}</b>, com saldo zero.
                  Sugestão: {{ dataBR(ciclo.sugestaoFechamento) }}
                  {{ ciclo.diasAtePrevisao < 0 ? '(a previsão, como no RH)' : '(ontem: hoje ainda está em andamento)' }}.
                </p>
                <p v-if="erroFechamento" class="mt-1 text-sm text-carimbo">{{ erroFechamento }}</p>
              </div>
              <p v-if="ciclo.diasEmAberto" class="rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm text-carimbo">
                {{ ciclo.diasEmAberto }} dia(s) do ciclo estão incompletos (faltou batida) e ficam fora do saldo.
                Se o RH já corrigiu, ajuste antes de fechar.
              </p>
              <div>
                <label for="ciclo-obs" class="rotulo">Observação (opcional)</label>
                <input id="ciclo-obs" v-model="observacao" type="text" maxlength="200" class="campo mt-1.5 font-sans" placeholder="Ex.: Zerado pelo RH no relatório de 25/05" />
              </div>
            </template>

            <!-- Período -->
            <template v-else>
              <p class="text-sm text-tinta-suave">
                O ciclo começa no dia seguinte ao último zeramento do RH e dura 6 meses. Corrija se o início
                não bater com o do RH: o saldo é recalculado na hora.
              </p>
              <div class="grid gap-4 sm:grid-cols-2">
                <div>
                  <label for="ciclo-inicio" class="rotulo">Início</label>
                  <input id="ciclo-inicio" ref="campoInicial" v-model="inicio" type="date" class="campo mt-1.5" @change="fimPrevisto = inicio ? previsaoPara(inicio) : ''" />
                </div>
                <div>
                  <label for="ciclo-fim" class="rotulo">Fechamento previsto</label>
                  <input id="ciclo-fim" v-model="fimPrevisto" type="date" class="campo mt-1.5" />
                </div>
              </div>
              <p class="text-xs text-tinta-suave">Os avisos de 30 e 15 dias usam a data de fechamento previsto.</p>
              <p v-if="erroPeriodo" class="text-sm text-carimbo">{{ erroPeriodo }}</p>
            </template>

            <p v-if="erro" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>

            <details v-if="historico.length" class="rounded-[3px] border border-linha px-4 py-3">
              <summary class="cursor-pointer text-sm font-semibold">Ciclos anteriores ({{ historico.length }})</summary>
              <ul class="mt-2 divide-y divide-linha/70 text-sm">
                <li v-for="c in historico" :key="c.id" class="flex items-baseline justify-between gap-3 py-1.5">
                  <span>{{ dataBR(c.dataInicio) }} a {{ dataBR(c.dataFim) }}<span v-if="c.observacao" class="block text-xs italic text-tinta-suave">{{ c.observacao }}</span></span>
                  <span class="carimbo font-semibold" :class="c.saldoSegundos < 0 ? 'text-carimbo' : 'text-credito'">{{ formatarSaldo(c.saldoSegundos) }}</span>
                </li>
              </ul>
            </details>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-dashed border-linha px-5 py-4 sm:flex-row sm:justify-end">
            <template v-if="modo === 'fechar' && resultado">
              <button type="button" class="botao-secundario" :disabled="enviando" @click="desfazer">Desfazer fechamento</button>
              <button type="button" class="botao-primario" @click="fechar">Concluir</button>
            </template>
            <template v-else>
              <button type="button" class="botao-secundario" :disabled="enviando" @click="fechar">Cancelar</button>
              <button
                type="submit"
                class="botao-primario"
                :disabled="enviando || (modo === 'fechar' ? !!erroFechamento : !!erroPeriodo)"
              >{{ enviando ? 'Salvando…' : modo === 'fechar' ? 'Fechar e recomeçar do zero' : 'Salvar período' }}</button>
            </template>
          </footer>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>
