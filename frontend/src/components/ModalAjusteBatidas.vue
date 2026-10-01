<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { usePontoStore } from '@/stores/ponto'
import { pontoApi } from '@/api/pontoApi'
import { comSegundos, dataCurta, diaSemanaCurto, paraSegundos, segundosAgora } from '@/utils/tempo'

/**
 * Ajuste manual das batidas de um dia: incluir a batida que o relógio não registrou,
 * corrigir ou remover batidas sem comprovante (ex.: correção feita pelo RH).
 * Batidas com PDF arquivado ficam travadas: o comprovante é a prova da marcação.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** yyyy-MM-dd */
  data: { type: String, default: null },
  /** Registro atual do dia (null quando o dia não tem nenhuma batida). */
  registro: { type: Object, default: null },
  /**
   * Batidas do relatório do RH (conciliação → "Ajuste manual"): a tela já abre com elas no lugar das
   * batidas sem comprovante que não batem com o RH. Batidas com PDF continuam travadas.
   */
  sugestaoRh: { type: Array, default: null },
  motivoSugerido: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'salvo'])

const store = usePontoStore()

const MAX_BATIDAS = 6
const ROTULOS = ['Entrada 1', 'Saída 1', 'Entrada 2', 'Saída 2', 'Entrada 3', 'Saída 3']
/** Duas batidas a até 1 minuto são a mesma (o PDF costuma marcar 1 s depois do RH). */
const MESMA_BATIDA = 60
const MOTIVOS = [
  'Corrigido pelo RH: falha no relógio de ponto',
  'Batida não registrada pelo sistema de ponto',
  'Comprovante não gerado; horário confirmado pelo RH',
]

const linhas = ref([])
const justificativa = ref('')
const comprovadas = ref(new Set())
const historico = ref([])
const carregandoContexto = ref(false)
const erroContexto = ref('')
const erroServidor = ref('')
const tentouEnviar = ref(false)
const botaoIncluir = ref(null)
let sequencia = 0

const novaLinha = (valor = '', original = null, ajustada = false) =>
  ({ id: ++sequencia, valor, original, ajustada })

async function reiniciar() {
  linhas.value = (props.registro?.batidas ?? [])
    .filter((b) => b.real)
    .map((b) => novaLinha(b.real, b.real, b.ajustada))
  justificativa.value = props.motivoSugerido ?? ''
  erroServidor.value = ''
  erroContexto.value = ''
  tentouEnviar.value = false
  comprovadas.value = new Set()
  historico.value = []
  if (!props.registro) return
  carregandoContexto.value = true
  try {
    const contexto = await pontoApi.contextoAjuste(props.data)
    comprovadas.value = new Set(contexto.comprovadas)
    historico.value = contexto.historico
  } catch (e) {
    erroContexto.value = `Não foi possível conferir os comprovantes do dia: ${e.message}`
  } finally {
    carregandoContexto.value = false
  }
  aplicarSugestaoRh()
}

/** Deixa o dia como o RH: tira batidas sem PDF que o RH não tem e inclui as que faltam. */
function aplicarSugestaoRh() {
  if (!props.sugestaoRh?.length) return
  const perto = (a, b) => Math.abs(paraSegundos(a) - paraSegundos(b)) <= MESMA_BATIDA
  const mantidas = linhas.value.filter((l) => travada(l) || props.sugestaoRh.some((h) => perto(h, l.valor)))
  // batida sem PDF vira exatamente o horário do RH
  for (const l of mantidas) {
    if (!travada(l)) l.valor = props.sugestaoRh.find((h) => perto(h, l.valor))
  }
  const novas = props.sugestaoRh
    .filter((h) => !mantidas.some((l) => perto(h, l.valor)))
    .map((h) => novaLinha(h))
  linhas.value = [...mantidas, ...novas].slice(0, MAX_BATIDAS)
}

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) return
    await reiniciar()
    await nextTick()
    botaoIncluir.value?.focus()
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

// ------------------------------------------------------------------ edição
const travada = (linha) => !!linha.original && comprovadas.value.has(linha.original)
const editavel = (linha) => !travada(linha) && !carregandoContexto.value

async function incluir() {
  if (linhas.value.length >= MAX_BATIDAS) return
  linhas.value.push(novaLinha())
  await nextTick()
  document.getElementById(`ajuste-hora-${linhas.value.at(-1).id}`)?.focus()
}

function remover(linha) {
  linhas.value = linhas.value.filter((l) => l.id !== linha.id)
}

/** Grade do dia pelo horário da pessoa. */
const periodosDoDia = computed(() => (props.data ? store.periodosDoDia(props.data) : []))

function preencherComGrade() {
  const horarios = periodosDoDia.value.flatMap((p) => [p.entrada, p.saida])
  if (!horarios.length) return
  linhas.value = horarios.map((h) => novaLinha(comSegundos(h.slice(0, 5))))
}

/** Horário no formato do servidor (o campo do navegador omite ":00" nos segundos). */
const horarioEnviado = (linha) => comSegundos(linha.valor)

const situacaoDaLinha = (linha) => {
  if (!linha.original) return 'nova'
  if (horarioEnviado(linha) !== linha.original) return 'alterada'
  return linha.ajustada ? 'ajustada' : null
}

// ------------------------------------------------------------------ validação
const ehHoje = computed(() => props.data === store.hoje)

const errosLinhas = computed(() => {
  const contagem = {}
  linhas.value.forEach((l) => l.valor && (contagem[comSegundos(l.valor)] = (contagem[comSegundos(l.valor)] ?? 0) + 1))
  const limite = segundosAgora() + 5 * 60
  return Object.fromEntries(linhas.value.map((l) => {
    if (!l.valor) return [l.id, 'Informe o horário.']
    if (contagem[comSegundos(l.valor)] > 1) return [l.id, 'Horário repetido.']
    if (ehHoje.value && !travada(l) && paraSegundos(l.valor) > limite) return [l.id, 'Ainda não aconteceu.']
    return [l.id, null]
  }))
})

const finais = computed(() =>
  linhas.value
    .map((l) => ({ linha: l, horario: horarioEnviado(l) }))
    .filter((x) => x.horario)
    .sort((a, b) => a.horario.localeCompare(b.horario)),
)

const mudou = computed(() => {
  const antes = (props.registro?.batidas ?? []).filter((b) => b.real).map((b) => b.real).sort()
  const depois = finais.value.map((x) => x.horario)
  return antes.length !== depois.length || antes.some((h, i) => h !== depois[i])
})

const erroGeral = computed(() => {
  if (!linhas.value.length) return 'Inclua ao menos uma batida. Para apagar o dia inteiro, use "Excluir registro".'
  if (!mudou.value) return 'Nenhuma batida foi alterada.'
  return null
})

const erroJustificativa = computed(() =>
  justificativa.value.trim().length < 5 ? 'Informe o motivo do ajuste (mínimo de 5 caracteres).' : null,
)

const valido = computed(() =>
  !erroGeral.value && !erroJustificativa.value && Object.values(errosLinhas.value).every((e) => e === null),
)

async function enviar() {
  tentouEnviar.value = true
  erroServidor.value = ''
  if (!valido.value) return
  try {
    const registro = await store.ajustarBatidas({
      data: props.data,
      horarios: finais.value.map((x) => x.horario),
      justificativa: justificativa.value.trim(),
    })
    emit('salvo', registro)
    emit('update:modelValue', false)
  } catch (e) {
    erroServidor.value = e.message
  }
}

const formatarMomento = (iso) =>
  new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
const listaHoras = (horarios) => (horarios.length ? horarios.join(' · ') : 'sem batidas')
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
          aria-labelledby="titulo-ajuste"
          class="cartao perfurado flex max-h-[94vh] w-full max-w-xl animate-surgir flex-col rounded-b-none sm:rounded-[3px]"
          novalidate
          @submit.prevent="enviar"
        >
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">Ajuste manual · {{ diaSemanaCurto(data) }} {{ dataCurta(data) }}</p>
              <h2 id="titulo-ajuste" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">
                Corrigir as batidas do dia
              </h2>
              <p class="mt-1 text-sm text-tinta-suave">
                Use quando o relógio falhou e o RH corrigiu no sistema dele. Batidas com PDF não podem ser alteradas.
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

          <div class="space-y-5 overflow-y-auto px-5 py-5">
            <!-- Batidas -->
            <fieldset>
              <legend class="rotulo">Batidas do dia</legend>
              <p v-if="carregandoContexto" class="mt-2 text-sm text-tinta-suave">Conferindo comprovantes…</p>
              <p v-if="erroContexto" class="mt-2 text-sm text-carimbo">{{ erroContexto }}</p>

              <ul class="mt-2 space-y-2">
                <li
                  v-for="l in linhas"
                  :key="l.id"
                  class="flex flex-wrap items-center gap-3 rounded-[3px] border px-3 py-2"
                  :class="travada(l) ? 'border-linha bg-papel/40' : 'border-linha bg-cartao'"
                >
                  <template v-if="travada(l)">
                    <span class="carimbo w-24 text-lg">{{ l.original }}</span>
                    <span class="flex items-center gap-1 rounded-[2px] bg-credito/10 px-1.5 py-0.5 text-[0.68rem] font-bold uppercase tracking-wide text-credito">
                      <svg viewBox="0 0 16 16" class="size-3" fill="currentColor" aria-hidden="true"><path d="M5 7V5a3 3 0 1 1 6 0v2h.5A1.5 1.5 0 0 1 13 8.5v5A1.5 1.5 0 0 1 11.5 15h-7A1.5 1.5 0 0 1 3 13.5v-5A1.5 1.5 0 0 1 4.5 7H5Zm1.5 0h3V5a1.5 1.5 0 1 0-3 0v2Z" /></svg>
                      comprovante PDF
                    </span>
                  </template>
                  <template v-else>
                    <label :for="`ajuste-hora-${l.id}`" class="sr-only">Horário da batida</label>
                    <input
                      :id="`ajuste-hora-${l.id}`"
                      v-model="l.valor"
                      type="time"
                      step="1"
                      class="campo w-44"
                      :disabled="!editavel(l)"
                      :aria-invalid="tentouEnviar && !!errosLinhas[l.id]"
                    />
                    <span
                      v-if="situacaoDaLinha(l)"
                      class="rounded-[2px] px-1.5 py-0.5 text-[0.68rem] font-bold uppercase tracking-wide"
                      :class="situacaoDaLinha(l) === 'ajustada' ? 'bg-tinta/10 text-tinta-suave' : 'bg-carimbo/10 text-carimbo'"
                    >{{ situacaoDaLinha(l) }}</span>
                    <span v-else class="text-xs text-tinta-suave">sem comprovante</span>
                    <button
                      type="button"
                      class="ml-auto rounded-[3px] px-2 py-1 text-xs font-semibold text-tinta-suave hover:bg-papel-escuro hover:text-carimbo"
                      :disabled="!editavel(l)"
                      @click="remover(l)"
                    >Remover</button>
                    <p v-if="tentouEnviar && errosLinhas[l.id]" class="w-full text-sm text-carimbo">{{ errosLinhas[l.id] }}</p>
                  </template>
                </li>
              </ul>

              <div class="mt-3 flex flex-wrap items-center gap-4">
                <button
                  ref="botaoIncluir"
                  type="button"
                  class="text-sm font-semibold text-tinta underline decoration-linha decoration-2 underline-offset-4 hover:decoration-tinta disabled:no-underline disabled:opacity-40"
                  :disabled="linhas.length >= MAX_BATIDAS || carregandoContexto"
                  @click="incluir"
                >+ Incluir batida esquecida</button>
                <button
                  v-if="!linhas.length && periodosDoDia.length"
                  type="button"
                  class="text-sm text-tinta-suave underline underline-offset-4 hover:text-tinta"
                  @click="preencherComGrade"
                >Preencher com a grade oficial</button>
                <span class="text-xs text-tinta-apagada">{{ linhas.length }}/{{ MAX_BATIDAS }} batidas</span>
              </div>
            </fieldset>

            <!-- Como vai ficar -->
            <section v-if="finais.length" aria-label="Como o dia vai ficar" class="rounded-[3px] border border-dashed border-tinta/40 px-4 py-3">
              <p class="rotulo">Como vai ficar</p>
              <ol class="mt-2 grid grid-cols-2 gap-2" :class="finais.length > 4 ? 'sm:grid-cols-3' : 'sm:grid-cols-4'">
                <li v-for="(rotulo, i) in ROTULOS.slice(0, Math.max(4, finais.length + (finais.length % 2)))" :key="rotulo">
                  <span class="block text-xs text-tinta-suave">{{ rotulo }}</span>
                  <span
                    class="carimbo text-lg"
                    :class="!finais[i] ? 'text-tinta-apagada' : situacaoDaLinha(finais[i].linha) === 'nova' || situacaoDaLinha(finais[i].linha) === 'alterada' ? 'font-semibold text-carimbo' : ''"
                  >{{ finais[i] ? finais[i].horario : '--:--:--' }}</span>
                </li>
              </ol>
              <p v-if="finais.length % 2 === 1" class="mt-2 text-xs text-carimbo">
                Com número ímpar de batidas o dia continua incompleto (fora do saldo).
              </p>
            </section>

            <!-- Justificativa -->
            <div>
              <label for="ajuste-motivo" class="rotulo">Motivo do ajuste</label>
              <textarea
                id="ajuste-motivo"
                v-model="justificativa"
                rows="2"
                maxlength="500"
                class="campo mt-1.5 resize-y font-sans"
                placeholder="Ex.: Corrigido pelo RH em 30/06 — falha no relógio de ponto"
                :aria-invalid="tentouEnviar && !!erroJustificativa"
              />
              <div class="mt-1.5 flex flex-wrap gap-1.5">
                <button
                  v-for="m in MOTIVOS"
                  :key="m"
                  type="button"
                  class="rounded-full border border-linha px-2.5 py-0.5 text-xs text-tinta-suave hover:border-tinta hover:text-tinta"
                  @click="justificativa = m"
                >{{ m }}</button>
              </div>
              <p v-if="tentouEnviar && erroJustificativa" class="mt-1.5 text-sm text-carimbo">{{ erroJustificativa }}</p>
              <p class="mt-1.5 text-xs text-tinta-apagada">Fica registrado no histórico, com seu usuário e a data, e aparece na auditoria.</p>
            </div>

            <p v-if="tentouEnviar && erroGeral" class="text-sm text-carimbo">{{ erroGeral }}</p>
            <p v-if="erroServidor" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
              {{ erroServidor }}
            </p>

            <!-- Histórico -->
            <details v-if="historico.length" class="rounded-[3px] border border-linha px-4 py-3">
              <summary class="cursor-pointer text-sm font-semibold">Ajustes anteriores ({{ historico.length }})</summary>
              <ul class="mt-2 space-y-2 text-sm">
                <li v-for="a in historico" :key="a.id" class="border-t border-linha/70 pt-2 first:border-0 first:pt-0">
                  <p class="text-xs text-tinta-suave">{{ formatarMomento(a.ajustadoEm) }} · {{ a.usuario }}</p>
                  <p class="carimbo">{{ listaHoras(a.antes) }} → {{ listaHoras(a.depois) }}</p>
                  <p class="italic text-tinta-suave">“{{ a.justificativa }}”</p>
                </li>
              </ul>
            </details>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-dashed border-linha px-5 py-4 sm:flex-row sm:justify-end">
            <button type="button" class="botao-secundario" :disabled="store.salvando" @click="fechar">Cancelar</button>
            <button type="submit" class="botao-primario" :disabled="store.salvando || carregandoContexto || (tentouEnviar && !valido)">
              {{ store.salvando ? 'Salvando…' : 'Salvar ajuste' }}
            </button>
          </footer>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>
