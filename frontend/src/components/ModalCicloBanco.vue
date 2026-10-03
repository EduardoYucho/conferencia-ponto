<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { usePontoStore } from '@/stores/ponto'
import { mensagemDe } from '@/utils/erros'
import { corDoSaldo, dataBR, saldoComSentido } from '@/utils/horas'
import Icone from '@/components/Icone.vue'

/**
 * Banco de horas, duas janelas num componente:
 *  - modo "fechar": "Fechar o banco de horas" — quando o RH zera o banco. O saldo até o dia escolhido fica
 *    guardado e a contagem recomeça do zero no dia seguinte (dá para desfazer).
 *  - modo "periodo": "Corrigir as datas do período" — o início e o fechamento previsto do período aberto.
 * As contas (saldo guardado, data em que recomeça, fechamento previsto) são do servidor.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 'fechar' | 'periodo' */
  modo: { type: String, default: 'fechar' },
  /** Período aberto do banco de horas (CicloBancoResponse). */
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
const tentou = ref(false)
const resultado = ref(null) // { fechado, novo } depois de fechar
const historico = ref([])
const campoInicial = ref(null)
const botaoConcluir = ref(null)
let quemAbriu = null

const fechando = computed(() => props.modo === 'fechar')

async function reiniciar() {
  erro.value = ''
  tentou.value = false
  resultado.value = null
  observacao.value = ''
  ultimoDia.value = props.ciclo?.sugestaoFechamento ?? store.hoje
  inicio.value = props.ciclo?.dataInicio ?? ''
  fimPrevisto.value = props.ciclo?.dataFimPrevista ?? ''
  historico.value = []
  try {
    historico.value = (await pontoApi.ciclos()).filter((c) => c.status === 'FECHADO')
  } catch {
    // os fechamentos anteriores são só para consulta: sem eles a janela funciona igual
  }
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
    const anteriores = reiniciar()
    await nextTick()
    campoInicial.value?.focus()
    await anteriores
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

/** Mudou o início: o fechamento previsto antigo deixa de valer; em branco, o servidor calcula o novo. */
function aoMudarInicio() {
  fimPrevisto.value = ''
}

// ------------------------------------------------------------------ ajuda de digitação (quem decide é o servidor)
const erroFechamento = computed(() => {
  if (!ultimoDia.value) return 'Informe o último dia que entra neste fechamento.'
  if (props.ciclo && ultimoDia.value < props.ciclo.dataInicio) return `Este período começou em ${dataBR(props.ciclo.dataInicio)}: escolha um dia a partir dessa data.`
  if (ultimoDia.value > store.hoje) return 'Escolha um dia até hoje.'
  return null
})

const erroPeriodo = computed(() => {
  if (!inicio.value) return 'Informe o dia em que o período começou.'
  if (fimPrevisto.value && fimPrevisto.value <= inicio.value) return 'O fechamento previsto precisa ser depois do início.'
  return null
})
const erroDoFormulario = computed(() => (fechando.value ? erroFechamento.value : erroPeriodo.value))

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
  tentou.value = true
  if (erroFechamento.value) return
  const fechamento = await gravar(() =>
    pontoApi.fecharCiclo({ ultimoDia: ultimoDia.value, observacao: observacao.value.trim() || null }))
  if (!fechamento.ok) return
  resultado.value = fechamento.dados
  emit('concluido', resultado.value)
  await nextTick()
  botaoConcluir.value?.focus()
}

async function desfazer() {
  const desfeito = await gravar(() => pontoApi.desfazerFechamento())
  if (!desfeito.ok) return
  resultado.value = null
  emit('update:modelValue', false)
  emit('concluido', null)
}

async function salvarPeriodo() {
  tentou.value = true
  if (erroPeriodo.value) return
  const salvo = await gravar(() => pontoApi.corrigirCiclo({ dataInicio: inicio.value, dataFimPrevista: fimPrevisto.value || null }))
  if (!salvo.ok) return
  emit('update:modelValue', false)
  emit('concluido', null)
}

const titulo = computed(() => {
  if (!fechando.value) return 'Corrigir as datas do período'
  return resultado.value ? 'Banco de horas fechado' : 'Fechar o banco de horas'
})
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="janela-fundo" @mousedown.self="fechar">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-ciclo"
        aria-describedby="explicacao-ciclo"
        class="janela"
        novalidate
        @submit.prevent="fechando ? confirmarFechamento() : salvarPeriodo()"
      >
        <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
          <div>
            <h2 id="titulo-ciclo" class="text-xl font-extrabold tracking-tight">{{ titulo }}</h2>
            <p id="explicacao-ciclo" class="mt-1 text-[0.95rem] text-texto-3">
              <template v-if="!ciclo">Ainda não há um período de banco de horas aberto.</template>
              <template v-else-if="!fechando">Use se as datas daqui não estiverem iguais às do RH. O saldo é refeito na hora.</template>
              <template v-else-if="resultado">O saldo ficou guardado e a contagem recomeçou do zero.</template>
              <template v-else>Faça isto quando o RH zerar o banco: o saldo fica guardado e a contagem recomeça do zero.</template>
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

        <div v-if="ciclo" class="flex flex-col gap-5 px-5 py-5">
          <!-- Fechado: o resultado -->
          <template v-if="fechando && resultado">
            <div class="rounded-xl border border-positivo-borda bg-positivo-suave px-4 py-3.5" role="status">
              <p class="flex items-center gap-2 text-[0.95rem] font-semibold text-positivo">
                <Icone nome="certo" /> Período de {{ dataBR(resultado.fechado.dataInicio) }} a {{ dataBR(resultado.fechado.dataFim) }} fechado com
              </p>
              <p class="mt-1 text-[1.9rem] leading-tight font-extrabold tracking-tight" :class="corDoSaldo(resultado.fechado.saldoSegundos)">
                {{ saldoComSentido(resultado.fechado.saldoSegundos) }}
              </p>
            </div>
            <dl class="flex flex-col gap-1.5 text-[0.95rem]">
              <div class="flex flex-wrap justify-between gap-x-4">
                <dt class="text-texto-3">A contagem recomeçou em</dt>
                <dd class="font-bold">{{ dataBR(resultado.novo.dataInicio) }}</dd>
              </div>
              <div class="flex flex-wrap justify-between gap-x-4">
                <dt class="text-texto-3">Próximo fechamento previsto</dt>
                <dd class="font-bold">{{ dataBR(resultado.novo.dataFimPrevista) }}</dd>
              </div>
              <div class="flex flex-wrap justify-between gap-x-4">
                <dt class="text-texto-3">Saldo do novo período</dt>
                <dd class="font-bold" :class="corDoSaldo(resultado.novo.saldoSegundos)">{{ saldoComSentido(resultado.novo.saldoSegundos) }}</dd>
              </div>
            </dl>
            <p class="text-sm text-texto-3">Fechou por engano? Dá para desfazer enquanto este for o último fechamento.</p>
          </template>

          <!-- Fechar -->
          <template v-else-if="fechando">
            <p class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 rounded-xl bg-superficie-2 px-4 py-3">
              <span class="text-[0.95rem] font-semibold text-texto-2">Saldo do banco hoje</span>
              <span class="text-2xl font-extrabold tracking-tight" :class="corDoSaldo(ciclo.saldoSegundos)">{{ saldoComSentido(ciclo.saldoSegundos) }}</span>
            </p>
            <div>
              <label for="ciclo-ultimo-dia" class="rotulo">Último dia que entra neste fechamento</label>
              <input
                id="ciclo-ultimo-dia"
                ref="campoInicial"
                v-model="ultimoDia"
                type="date"
                class="campo mt-1.5"
                :min="ciclo.dataInicio"
                :max="store.hoje"
                :aria-invalid="tentou && !!erroFechamento"
                aria-describedby="ciclo-ultimo-dia-ajuda"
              />
              <p v-if="tentou && erroFechamento" class="mt-1.5 text-sm font-semibold text-negativo">{{ erroFechamento }}</p>
              <p id="ciclo-ultimo-dia-ajuda" class="mt-1.5 text-sm text-texto-3">
                O saldo é contado até este dia; a contagem recomeça do zero no dia seguinte.
                <template v-if="ciclo.sugestaoFechamento">
                  Sugestão: <b class="text-texto-2">{{ dataBR(ciclo.sugestaoFechamento) }}</b>
                  {{ ciclo.diasAtePrevisao < 0 ? '(a data prevista, como no RH)' : '(ontem, porque o dia de hoje ainda não terminou)' }}.
                </template>
              </p>
            </div>
            <p v-if="ciclo.diasEmAberto" class="aviso-atencao flex items-start gap-2.5">
              <Icone nome="alerta" class="mt-0.5" />
              <span class="text-[0.95rem]">
                {{ ciclo.diasEmAberto === 1 ? '1 dia está com batida faltando e fica' : `${ciclo.diasEmAberto} dias estão com batida faltando e ficam` }}
                fora do saldo. Se o RH já corrigiu, corrija {{ ciclo.diasEmAberto === 1 ? 'esse dia' : 'esses dias' }} antes de fechar.
              </span>
            </p>
            <div>
              <label for="ciclo-obs" class="rotulo">Observação (opcional)</label>
              <input id="ciclo-obs" v-model="observacao" type="text" maxlength="200" class="campo mt-1.5" placeholder="Ex.: zerado pelo RH no relatório de 25/05" />
            </div>
          </template>

          <!-- Datas do período -->
          <template v-else>
            <div class="grid gap-x-4 gap-y-5 sm:grid-cols-2">
              <div>
                <label for="ciclo-inicio" class="rotulo">O período começou em</label>
                <input
                  id="ciclo-inicio"
                  ref="campoInicial"
                  v-model="inicio"
                  type="date"
                  class="campo mt-1.5"
                  :aria-invalid="tentou && !inicio"
                  aria-describedby="ciclo-inicio-ajuda"
                  @change="aoMudarInicio"
                />
                <p id="ciclo-inicio-ajuda" class="mt-1.5 text-sm text-texto-3">É o dia seguinte ao último fechamento do RH.</p>
              </div>
              <div>
                <label for="ciclo-fim" class="rotulo">Fechamento previsto</label>
                <input id="ciclo-fim" v-model="fimPrevisto" type="date" :min="inicio || undefined" class="campo mt-1.5" aria-describedby="ciclo-fim-ajuda" />
                <p id="ciclo-fim-ajuda" class="mt-1.5 text-sm text-texto-3">
                  {{ fimPrevisto ? 'Os avisos de prazo usam esta data.' : 'Em branco, o sistema calcula pela duração do banco de horas.' }}
                </p>
              </div>
            </div>
            <p v-if="tentou && erroPeriodo" class="aviso-erro" role="alert">{{ erroPeriodo }}</p>
          </template>

          <p v-if="erro" class="aviso-erro" role="alert">{{ erro }}</p>

          <details v-if="historico.length" class="group rounded-xl border border-borda px-4 py-1">
            <summary class="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 text-[0.95rem] font-semibold [&::-webkit-details-marker]:hidden">
              Fechamentos anteriores ({{ historico.length }})
              <Icone nome="abaixo" tamanho="18" class="text-texto-3 transition-transform group-open:rotate-180" />
            </summary>
            <ul class="divide-y divide-borda pb-1">
              <li v-for="c in historico" :key="c.id" class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-0.5 py-2 text-[0.95rem]">
                <span>{{ dataBR(c.dataInicio) }} a {{ dataBR(c.dataFim) }}</span>
                <b :class="corDoSaldo(c.saldoSegundos)">{{ saldoComSentido(c.saldoSegundos) }}</b>
                <span v-if="c.observacao" class="w-full text-sm text-texto-3">Observação: {{ c.observacao }}</span>
              </li>
            </ul>
          </details>
        </div>

        <footer class="sticky bottom-0 flex flex-wrap justify-end gap-2 border-t border-borda bg-superficie px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <button v-if="!ciclo" ref="campoInicial" type="button" class="botao-secundario min-h-11" @click="fechar">Fechar</button>
          <template v-else-if="fechando && resultado">
            <button type="button" class="botao-secundario min-h-11" :disabled="enviando" @click="desfazer">
              {{ enviando ? 'Desfazendo…' : 'Desfazer o fechamento' }}
            </button>
            <button ref="botaoConcluir" type="button" class="botao-primario min-h-11 grow sm:grow-0" :disabled="enviando" @click="fechar">Concluir</button>
          </template>
          <template v-else>
            <button type="button" class="botao-secundario min-h-11" :disabled="enviando" @click="fechar">Cancelar</button>
            <button type="submit" class="botao-primario min-h-11 grow sm:grow-0" :disabled="enviando || (tentou && !!erroDoFormulario)">
              {{ enviando ? 'Salvando…' : fechando ? 'Fechar o banco de horas' : 'Salvar as datas' }}
            </button>
          </template>
        </footer>
      </form>
    </div>
  </Teleport>
</template>
