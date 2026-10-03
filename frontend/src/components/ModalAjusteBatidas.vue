<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { usePontoStore } from '@/stores/ponto'
import { pontoApi } from '@/api/pontoApi'
import { mensagemDe } from '@/utils/erros'
import { diaMes, momento } from '@/utils/horas'
import { comSegundos, diaSemanaCurto, paraSegundos } from '@/utils/tempo'
import Icone from '@/components/Icone.vue'

/**
 * "Corrigir os horários do dia": incluir a batida que faltou, corrigir ou remover um horário, sempre com o
 * motivo. Batidas com comprovante (PDF) ficam travadas: o comprovante é a prova da marcação.
 * A tela só ajuda a digitar; quem aceita ou recusa o ajuste é o servidor, e a mensagem dele aparece aqui.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** yyyy-MM-dd */
  data: { type: String, default: null },
  /** Registro atual do dia (null quando o dia não tem nenhuma batida). */
  registro: { type: Object, default: null },
  /**
   * Horários do relatório do RH (tela "Conferir com o RH"): a janela já abre com eles no lugar das batidas sem
   * comprovante que não batem com o RH. Batidas com PDF continuam travadas.
   */
  sugestaoRh: { type: Array, default: null },
  motivoSugerido: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'salvo'])

const store = usePontoStore()

/** Quantas batidas cabem num dia (vem do servidor; o número é só a reserva). */
const maximo = computed(() => store.limites?.batidasPorDia ?? 6)
/** Dois horários a até 1 minuto são a mesma batida (o PDF costuma marcar 1 s depois do RH). */
const MESMA_BATIDA = 60
const MOTIVOS = [
  'O relógio de ponto falhou e o RH corrigiu',
  'Esqueci de bater o ponto',
  'O comprovante não foi gerado; horário confirmado pelo RH',
]

const linhas = ref([])
const justificativa = ref('')
const comprovadas = ref(new Set())
const historico = ref([])
/** Nomes da 1ª, 2ª... batida do dia pelo horário da pessoa ("Entrada", "Saída p/ almoço"...): vêm do servidor. */
const nomes = ref([])
const carregandoContexto = ref(false)
const erroContexto = ref('')
const erroServidor = ref('')
const tentouEnviar = ref(false)
const botaoIncluir = ref(null)
let sequencia = 0
let pedidoContexto = 0
let quemAbriu = null

const novaLinha = (valor = '', original = null, ajustada = false) =>
  ({ id: ++sequencia, valor, original, ajustada })

/** As linhas ficam na ordem do dia (as ainda sem horário, no fim). @returns {boolean} alguma mudou de lugar */
function ordenar() {
  const chave = (l) => (l.valor ? comSegundos(l.valor) : '99')
  const emOrdem = [...linhas.value].sort((a, b) => chave(a).localeCompare(chave(b)))
  if (emOrdem.every((l, i) => l === linhas.value[i])) return false
  linhas.value = emOrdem
  return true
}

/**
 * A pessoa terminou de digitar um horário (saiu do campo): só então a linha vai para o lugar dela — nunca a cada
 * tecla, para não sair do lugar no meio da digitação. O foco continua onde a pessoa o colocou.
 */
async function aoSairDoHorario(evento) {
  const proximo = evento.relatedTarget
  if (!ordenar()) return
  await nextTick()
  if (proximo?.isConnected && document.activeElement !== proximo) proximo.focus()
}

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
  nomes.value = []
  // a janela pode ser reaberta em outro dia antes de a resposta chegar: só a do último pedido entra
  const pedido = ++pedidoContexto
  // dia sem registro não tem comprovante a conferir: já dá para digitar (os nomes das batidas chegam em seguida)
  const temRegistro = !!props.registro
  carregandoContexto.value = temRegistro
  if (!temRegistro) aplicarSugestaoRh()
  try {
    const contexto = await pontoApi.contextoAjuste(props.data)
    if (pedido !== pedidoContexto) return
    comprovadas.value = new Set(contexto.comprovadas ?? [])
    historico.value = contexto.historico ?? []
    nomes.value = contexto.nomes ?? []
  } catch (e) {
    if (pedido !== pedidoContexto) return
    if (temRegistro) erroContexto.value = `Não foi possível conferir os comprovantes do dia. ${mensagemDe(e)}`
  } finally {
    if (pedido === pedidoContexto) carregandoContexto.value = false
  }
  if (temRegistro) aplicarSugestaoRh()
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
  linhas.value = [...mantidas, ...novas].slice(0, maximo.value)
  ordenar()
}

/** Foco no primeiro horário que dá para mexer; sem nenhum, no botão de incluir. */
function focarPrimeiroCampo() {
  const primeira = linhas.value.find((l) => editavel(l))
  const campo = primeira ? document.getElementById(`ajuste-hora-${primeira.id}`) : null
  if (campo) campo.focus()
  else botaoIncluir.value?.focus()
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
    const contexto = reiniciar()
    if (props.registro) await contexto // enquanto os comprovantes são conferidos, os horários ficam travados
    await nextTick()
    focarPrimeiroCampo()
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
const cheio = computed(() => linhas.value.length >= maximo.value)

async function incluir() {
  if (cheio.value) return
  linhas.value.push(novaLinha())
  await nextTick()
  document.getElementById(`ajuste-hora-${linhas.value.at(-1).id}`)?.focus()
}

async function remover(linha) {
  linhas.value = linhas.value.filter((l) => l.id !== linha.id)
  await nextTick()
  botaoIncluir.value?.focus()
}

/** O horário previsto para o dia (do horário de trabalho da pessoa). */
const periodosDoDia = computed(() => (props.data ? store.periodosDoDia(props.data) : []))
const horariosPrevistos = computed(() => periodosDoDia.value.flatMap((p) => [p.entrada, p.saida]).map((h) => h.slice(0, 5)))

function preencherComPrevisto() {
  if (!horariosPrevistos.value.length) return
  linhas.value = horariosPrevistos.value.slice(0, maximo.value).map((h) => novaLinha(comSegundos(h)))
}

/** Horário no formato do servidor (o campo do navegador omite ":00" nos segundos). */
const horarioEnviado = (linha) => comSegundos(linha.valor)

/** 'nova' | 'alterada' | 'ajustada' (já tinha sido informada à mão) | null */
const situacaoDaLinha = (linha) => {
  if (!linha.original) return 'nova'
  if (horarioEnviado(linha) !== linha.original) return 'alterada'
  return linha.ajustada ? 'ajustada' : null
}
const SELOS = {
  nova: { texto: 'nova', classe: 'selo-info' },
  alterada: { texto: 'alterada', classe: 'selo-atencao' },
  ajustada: { texto: 'informada à mão', classe: 'selo-neutro' },
}

/** Os horários preenchidos, na ordem do dia (é assim que o servidor os lê). */
const finais = computed(() =>
  linhas.value
    .map((l) => ({ linha: l, horario: horarioEnviado(l) }))
    .filter((x) => x.horario)
    .sort((a, b) => a.horario.localeCompare(b.horario)),
)

/** Número ímpar de horários: falta o par do último (o servidor é quem diz como o dia fica depois de salvar). */
const faltaUm = computed(() => finais.value.length % 2 === 1)
/** Sem os nomes do servidor (versão antiga ou falha na consulta), vale a ordem: "1ª batida (entrada)". */
const nomeDaPosicao = (i) => nomes.value[i] ?? `${i + 1}ª batida (${i % 2 === 0 ? 'entrada' : 'saída'})`
function nomeDaLinha(linha) {
  const posicao = finais.value.findIndex((x) => x.linha.id === linha.id)
  return posicao < 0 ? 'Novo horário' : nomeDaPosicao(posicao)
}

// ------------------------------------------------------------------ ajuda de digitação (quem decide é o servidor)
const errosLinhas = computed(() => {
  const contagem = {}
  linhas.value.forEach((l) => l.valor && (contagem[comSegundos(l.valor)] = (contagem[comSegundos(l.valor)] ?? 0) + 1))
  return Object.fromEntries(linhas.value.map((l) => {
    if (!l.valor) return [l.id, 'Informe o horário (ou remova esta linha).']
    if (contagem[comSegundos(l.valor)] > 1) return [l.id, 'Este horário está repetido.']
    return [l.id, null]
  }))
})

const mudou = computed(() => {
  const antes = (props.registro?.batidas ?? []).filter((b) => b.real).map((b) => b.real).sort()
  const depois = finais.value.map((x) => x.horario)
  return antes.length !== depois.length || antes.some((h, i) => h !== depois[i])
})

const erroGeral = computed(() => {
  if (!linhas.value.length) return 'Inclua ao menos um horário. Para apagar o dia inteiro, use "Apagar o registro do dia" nos detalhes do dia.'
  if (!mudou.value) return 'Nenhum horário foi alterado.'
  return null
})

const erroJustificativa = computed(() => (justificativa.value.trim() ? null : 'Escreva o motivo da correção.'))

const valido = computed(() =>
  !erroGeral.value && !erroJustificativa.value && Object.values(errosLinhas.value).every((e) => e === null),
)

async function enviar() {
  tentouEnviar.value = true
  erroServidor.value = ''
  if (!valido.value) return
  let registro
  try {
    registro = await store.ajustarBatidas({
      data: props.data,
      horarios: finais.value.map((x) => x.horario),
      justificativa: justificativa.value.trim(),
    })
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    return
  }
  // salvou: a janela fecha antes de avisar a tela (nada depois daqui pode parecer falha da gravação)
  emit('update:modelValue', false)
  emit('salvo', registro)
}

const listaHoras = (horarios) => (horarios.length ? horarios.map((h) => h.slice(0, 5)).join(' · ') : 'sem batidas')
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="janela-fundo" @mousedown.self="fechar">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-ajuste"
        aria-describedby="explicacao-ajuste"
        class="janela max-w-xl"
        novalidate
        @submit.prevent="enviar"
      >
        <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
          <div>
            <h2 id="titulo-ajuste" class="text-xl font-extrabold tracking-tight">Corrigir os horários do dia</h2>
            <p id="explicacao-ajuste" class="mt-1 text-[0.95rem] text-texto-3">
              <b v-if="data" class="text-texto-2">{{ diaSemanaCurto(data) }}, {{ diaMes(data) }}.</b>
              Inclua a batida que faltou, corrija ou remova um horário. Batidas com comprovante não mudam.
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
          <p v-if="sugestaoRh?.length && !carregandoContexto" class="rounded-xl border border-primaria-borda bg-primaria-suave px-3.5 py-2.5 text-sm text-primaria">
            <template v-if="mudou">Os horários abaixo já estão como no relatório do RH. Confira e salve.</template>
            <template v-else>As batidas deste dia têm comprovante e ficam como estão: nada foi trocado pelos horários do RH.</template>
          </p>

          <!-- Horários -->
          <fieldset>
            <legend class="rotulo">Horários do dia</legend>
            <p v-if="carregandoContexto" class="mt-1.5 text-sm text-texto-3" role="status">Conferindo os comprovantes do dia…</p>
            <p v-if="erroContexto" role="alert" class="aviso-erro mt-2 flex flex-wrap items-center justify-between gap-2">
              <span>{{ erroContexto }}</span>
              <button type="button" class="botao-linha" @click="reiniciar">Tentar de novo</button>
            </p>

            <ul v-if="linhas.length" class="mt-2 flex flex-col gap-2">
              <li
                v-for="l in linhas"
                :key="l.id"
                class="rounded-xl border border-borda px-3 py-2.5"
                :class="travada(l) ? 'bg-superficie-2' : 'bg-superficie'"
              >
                <div v-if="travada(l)" class="flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5">
                  <div>
                    <p class="text-sm font-semibold text-texto-3">{{ nomeDaLinha(l) }}</p>
                    <p class="text-xl font-bold">{{ l.original }}</p>
                  </div>
                  <span class="selo selo-neutro" title="O comprovante em PDF é a prova desta batida: ela não pode ser alterada nem removida">
                    <Icone nome="cadeado" tamanho="16" /> com comprovante
                  </span>
                </div>
                <template v-else>
                  <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                    <label :for="`ajuste-hora-${l.id}`" class="rotulo">{{ nomeDaLinha(l) }}</label>
                    <span v-if="situacaoDaLinha(l)" class="selo py-0.5!" :class="SELOS[situacaoDaLinha(l)].classe">{{ SELOS[situacaoDaLinha(l)].texto }}</span>
                    <span v-else class="text-sm text-texto-3">sem comprovante</span>
                  </div>
                  <div class="mt-1.5 flex items-center justify-between gap-3">
                    <input
                      :id="`ajuste-hora-${l.id}`"
                      v-model="l.valor"
                      type="time"
                      step="1"
                      class="campo w-44"
                      :disabled="!editavel(l)"
                      :aria-invalid="tentouEnviar && !!errosLinhas[l.id]"
                      :aria-describedby="tentouEnviar && errosLinhas[l.id] ? `ajuste-erro-${l.id}` : undefined"
                      @blur="aoSairDoHorario"
                    />
                    <button
                      type="button"
                      class="botao-linha"
                      :disabled="!editavel(l)"
                      :aria-label="`Remover o horário ${l.valor ? l.valor.slice(0, 5) : 'novo'}`"
                      @click="remover(l)"
                    ><Icone nome="lixeira" tamanho="18" /> Remover</button>
                  </div>
                </template>
                <p v-if="tentouEnviar && errosLinhas[l.id]" :id="`ajuste-erro-${l.id}`" class="mt-1.5 text-sm font-semibold text-negativo">{{ errosLinhas[l.id] }}</p>
              </li>
            </ul>
            <p v-else-if="!carregandoContexto" class="mt-2 rounded-xl bg-superficie-2 px-3.5 py-3 text-[0.95rem] text-texto-3">
              Este dia ainda não tem nenhum horário.
            </p>

            <!-- número ímpar de horários: fica faltando o par do último -->
            <div v-if="faltaUm" class="mt-2 rounded-xl border border-dashed border-borda-forte px-3 py-2.5" role="status">
              <p class="text-sm font-semibold text-texto-3">{{ nomeDaPosicao(finais.length) }}</p>
              <p class="text-base font-bold text-negativo">falta este horário</p>
              <p class="mt-0.5 text-sm text-texto-2">Com um número ímpar de horários o dia continua para corrigir.</p>
            </div>

            <div class="mt-3 flex flex-wrap items-center gap-2.5">
              <button
                ref="botaoIncluir"
                type="button"
                class="botao-secundario min-h-11"
                :disabled="cheio || carregandoContexto"
                @click="incluir"
              ><Icone nome="somar" tamanho="18" /> Incluir um horário</button>
              <button
                v-if="!linhas.length && horariosPrevistos.length"
                type="button"
                class="botao-secundario min-h-11"
                :title="`Preenche com ${horariosPrevistos.join(' · ')}`"
                @click="preencherComPrevisto"
              >Preencher com o horário previsto</button>
              <span class="text-sm text-texto-3">
                {{ linhas.length }} de {{ maximo }} horários<template v-if="cheio"> (o máximo por dia)</template>
              </span>
            </div>
          </fieldset>

          <!-- Motivo -->
          <div>
            <label for="ajuste-motivo" class="rotulo">Motivo da correção</label>
            <textarea
              id="ajuste-motivo"
              v-model="justificativa"
              rows="2"
              maxlength="500"
              class="campo mt-1.5 resize-y"
              placeholder="Ex.: o relógio de ponto falhou e o RH corrigiu"
              :aria-invalid="tentouEnviar && !!erroJustificativa"
              aria-describedby="ajuste-motivo-ajuda"
            />
            <p v-if="tentouEnviar && erroJustificativa" class="mt-1.5 text-sm font-semibold text-negativo">{{ erroJustificativa }}</p>
            <div class="mt-2 flex flex-wrap items-center gap-2" role="group" aria-label="Motivos mais comuns">
              <button
                v-for="m in MOTIVOS"
                :key="m"
                type="button"
                class="min-h-9 rounded-full border border-borda-forte bg-superficie px-3 py-1 text-left text-sm font-semibold text-texto-2 transition-colors hover:bg-neutro"
                @click="justificativa = m"
              >{{ m }}</button>
            </div>
            <p id="ajuste-motivo-ajuda" class="mt-2 text-sm text-texto-3">O motivo fica guardado no histórico do dia, com o seu nome e a data.</p>
          </div>

          <p v-if="tentouEnviar && erroGeral" class="aviso-erro" role="alert">{{ erroGeral }}</p>
          <p v-if="erroServidor" class="aviso-erro" role="alert">{{ erroServidor }}</p>

          <!-- Histórico -->
          <details v-if="historico.length" class="group rounded-xl border border-borda px-4 py-1">
            <summary class="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 text-[0.95rem] font-semibold [&::-webkit-details-marker]:hidden">
              Correções anteriores deste dia ({{ historico.length }})
              <Icone nome="abaixo" tamanho="18" class="text-texto-3 transition-transform group-open:rotate-180" />
            </summary>
            <ul class="flex flex-col gap-2 pb-3">
              <li v-for="a in historico" :key="a.id" class="rounded-lg border border-borda bg-superficie-2 px-3 py-2 text-[0.95rem]">
                <p class="text-sm text-texto-3">{{ momento(a.ajustadoEm) }} · por {{ a.usuario }}</p>
                <p>{{ listaHoras(a.antes) }} <span class="text-texto-4" aria-hidden="true">→</span><span class="sr-only"> passou para </span> <b>{{ listaHoras(a.depois) }}</b></p>
                <p class="text-texto-2">Motivo: {{ a.justificativa }}</p>
              </li>
            </ul>
          </details>
        </div>

        <footer class="sticky bottom-0 flex flex-wrap justify-end gap-2 border-t border-borda bg-superficie px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <button type="button" class="botao-secundario min-h-11" :disabled="store.salvando" @click="fechar">Cancelar</button>
          <button type="submit" class="botao-primario min-h-11 grow sm:grow-0" :disabled="store.salvando || carregandoContexto || (tentouEnviar && !valido)">
            {{ store.salvando ? 'Salvando…' : 'Salvar os horários' }}
          </button>
        </footer>
      </form>
    </div>
  </Teleport>
</template>
