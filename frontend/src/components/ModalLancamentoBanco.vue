<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { usePontoStore } from '@/stores/ponto'
import { mensagemDe } from '@/utils/erros'
import { corDoSaldo, dataBR, diaMes, duracao, saldo, saldoComSentido } from '@/utils/horas'
import { diaSemanaCurto, duracaoEmSegundos, normalizarDuracao } from '@/utils/tempo'
import Icone from '@/components/Icone.vue'

/**
 * "Usar ou somar horas do banco": usar as horas a favor (folga, saída mais cedo, horas pagas pela empresa) ou
 * somar horas que faltaram entrar (correção do RH). As batidas do dia não mudam: o lançamento entra direto no
 * saldo do mês e do banco. Quem aceita é o servidor; o saldo novo aparece na tela depois de salvar.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** Data sugerida (yyyy-MM-dd); sem ela, hoje. */
  dataInicial: { type: String, default: null },
  /** Já vem preenchido (ex.: folga compensada = um dia inteiro do horário). */
  duracaoInicial: { type: String, default: '' },
  descricaoInicial: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'salvo'])

const store = usePontoStore()

const TIPOS = [
  { valor: 'DEBITO', icone: 'subtrair', titulo: 'Usar horas do banco', exemplo: 'Folga, saída mais cedo, horas pagas' },
  { valor: 'CREDITO', icone: 'somar', titulo: 'Somar horas ao banco', exemplo: 'Correção do RH, horas que faltaram' },
]
/** Motivos mais comuns de cada caso (um toque preenche o campo). */
const SUGESTOES = {
  DEBITO: ['Folga compensada', 'Saída mais cedo compensada', 'Horas pagas pela empresa', 'Compensação de horas'],
  CREDITO: ['Correção do RH', 'Horas que faltaram entrar'],
}

const data = ref('')
const sentido = ref('DEBITO')
const duracaoTexto = ref('')
const descricao = ref('')
const tentou = ref(false)
const erroServidor = ref('')
const campoDuracao = ref(null)
const campoMotivo = ref(null)
let quemAbriu = null

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) {
      quemAbriu?.focus?.()
      quemAbriu = null
      return
    }
    quemAbriu = document.activeElement
    data.value = props.dataInicial ?? store.hoje
    sentido.value = 'DEBITO'
    duracaoTexto.value = props.duracaoInicial
    descricao.value = props.descricaoInicial
    tentou.value = false
    erroServidor.value = ''
    await nextTick()
    // o primeiro campo que ainda falta preencher
    if (!duracaoTexto.value || descricao.value) campoDuracao.value?.focus()
    else campoMotivo.value?.focus()
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

const usando = computed(() => sentido.value === 'DEBITO')
/** Início do período atual do banco de horas (lançamento anterior a ele o servidor recusa). */
const inicioDoPeriodo = computed(() => store.ciclo?.dataInicio ?? null)
/** O que foi digitado, no formato que o servidor recebe ("04:30"); null = não deu para entender. */
const duracaoLida = computed(() => normalizarDuracao(duracaoTexto.value))
const segundos = computed(() => (duracaoLida.value ? duracaoEmSegundos(duracaoLida.value) * (usando.value ? -1 : 1) : 0))

// ------------------------------------------------------------- ajuda de digitação (quem decide é o servidor)
const erros = computed(() => ({
  duracao: !duracaoTexto.value.trim()
    ? 'Informe quantas horas.'
    : !duracaoLida.value ? 'Não entendi. Escreva as horas e os minutos, por exemplo: 4, 4:30 ou 4h30.' : null,
  data: !data.value
    ? 'Informe a data.'
    : inicioDoPeriodo.value && data.value < inicioDoPeriodo.value
      ? `O período atual do banco de horas começou em ${dataBR(inicioDoPeriodo.value)}: escolha uma data a partir desse dia.`
      : null,
  descricao: !descricao.value.trim() ? 'Escreva o motivo.' : null,
}))
const valido = computed(() => Object.values(erros.value).every((e) => !e))

/** Atalhos: "dia inteiro" e "meio dia" pelo horário da pessoa no dia escolhido (ou o dia mais comum do horário). */
const cargaDoDia = computed(() => (data.value && store.cargaDoDia(data.value)) || store.cargaDiaInteiro || 0)
const ATALHOS = computed(() => (cargaDoDia.value
  ? [
      { rotulo: 'Dia inteiro', segundos: cargaDoDia.value },
      { rotulo: 'Meio dia', segundos: Math.round(cargaDoDia.value / 2 / 60) * 60 },
    ]
  : []))
function preencher(atalho) {
  // valor do campo, no formato que ele aceita ("08:48")
  const horas = Math.floor(atalho.segundos / 3600)
  const minutos = Math.floor((atalho.segundos % 3600) / 60)
  duracaoTexto.value = `${String(horas).padStart(2, '0')}:${String(minutos).padStart(2, '0')}`
  campoDuracao.value?.focus()
}

async function enviar() {
  tentou.value = true
  erroServidor.value = ''
  if (!valido.value) return
  let salvo
  try {
    salvo = await store.lancarNoBanco({
      data: data.value,
      duracao: duracaoLida.value,
      sentido: sentido.value,
      descricao: descricao.value.trim(),
    })
  } catch (e) {
    erroServidor.value = mensagemDe(e)
    return
  }
  // lançou: a janela fecha antes de avisar a tela (nada depois daqui pode parecer falha e levar a lançar de novo)
  emit('update:modelValue', false)
  emit('salvo', salvo)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="janela-fundo" @mousedown.self="fechar">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-lanc-banco"
        aria-describedby="explicacao-lanc-banco"
        class="janela"
        novalidate
        @submit.prevent="enviar"
      >
        <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
          <div>
            <h2 id="titulo-lanc-banco" class="text-xl font-extrabold tracking-tight">Usar ou somar horas do banco</h2>
            <p id="explicacao-lanc-banco" class="mt-1 text-[0.95rem] text-texto-3">
              As batidas do dia não mudam: as horas entram direto no saldo do banco de horas.
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
          <!-- Usar ou somar -->
          <fieldset>
            <legend class="rotulo">O que você quer fazer?</legend>
            <div class="mt-1.5 grid gap-2 sm:grid-cols-2">
              <label
                v-for="t in TIPOS"
                :key="t.valor"
                class="flex cursor-pointer items-start gap-2.5 rounded-xl border px-3.5 py-3 transition-colors has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-primaria"
                :class="sentido === t.valor ? 'border-primaria bg-primaria-suave' : 'border-borda-forte bg-superficie hover:bg-neutro'"
              >
                <input v-model="sentido" type="radio" name="lanc-banco-sentido" :value="t.valor" class="sr-only" />
                <Icone :nome="t.icone" class="mt-0.5" :class="sentido === t.valor ? 'text-primaria' : 'text-texto-3'" />
                <span>
                  <span class="block text-[0.95rem] font-bold" :class="sentido === t.valor ? 'text-primaria' : 'text-texto'">{{ t.titulo }}</span>
                  <span class="block text-sm text-texto-2">{{ t.exemplo }}</span>
                </span>
              </label>
            </div>
          </fieldset>

          <div class="grid gap-x-4 gap-y-5 sm:grid-cols-2">
            <div>
              <label for="lanc-banco-horas" class="rotulo">Quantas horas</label>
              <input
                id="lanc-banco-horas"
                ref="campoDuracao"
                v-model="duracaoTexto"
                type="text"
                inputmode="text"
                autocomplete="off"
                placeholder="Ex.: 4:30"
                class="campo mt-1.5"
                :aria-invalid="tentou && !!erros.duracao"
                aria-describedby="lanc-banco-horas-ajuda"
              />
              <p id="lanc-banco-horas-ajuda" class="mt-1.5 text-sm">
                <span v-if="tentou && erros.duracao" class="font-semibold text-negativo">{{ erros.duracao }}</span>
                <span v-else-if="duracaoLida" class="text-texto-2">= {{ duracao(duracaoEmSegundos(duracaoLida)) }}</span>
                <span v-else class="text-texto-3">Pode escrever 4, 4:30 ou 4h30.</span>
              </p>
              <div v-if="ATALHOS.length" class="mt-2 flex flex-wrap gap-2" role="group" aria-label="Atalhos para as horas">
                <button
                  v-for="a in ATALHOS"
                  :key="a.rotulo"
                  type="button"
                  class="min-h-9 rounded-full border border-borda-forte bg-superficie px-3 py-1 text-sm font-semibold text-texto-2 transition-colors hover:bg-neutro"
                  :title="duracao(a.segundos)"
                  :aria-label="`${a.rotulo}: ${duracao(a.segundos)}`"
                  @click="preencher(a)"
                >{{ a.rotulo }}</button>
              </div>
            </div>
            <div>
              <label for="lanc-banco-data" class="rotulo">Em que dia</label>
              <input
                id="lanc-banco-data"
                v-model="data"
                type="date"
                :min="inicioDoPeriodo ?? undefined"
                class="campo mt-1.5"
                :aria-invalid="tentou && !!erros.data"
                aria-describedby="lanc-banco-data-ajuda"
              />
              <p id="lanc-banco-data-ajuda" class="mt-1.5 text-sm">
                <span v-if="tentou && erros.data" class="font-semibold text-negativo">{{ erros.data }}</span>
                <span v-else-if="data" class="text-texto-3">{{ diaSemanaCurto(data) }}, {{ diaMes(data) }}</span>
              </p>
            </div>
          </div>

          <div>
            <label for="lanc-banco-motivo" class="rotulo">Motivo</label>
            <input
              id="lanc-banco-motivo"
              ref="campoMotivo"
              v-model="descricao"
              type="text"
              maxlength="200"
              class="campo mt-1.5"
              placeholder="Ex.: folga compensada"
              :aria-invalid="tentou && !!erros.descricao"
            />
            <p v-if="tentou && erros.descricao" class="mt-1.5 text-sm font-semibold text-negativo">{{ erros.descricao }}</p>
            <div class="mt-2 flex flex-wrap gap-2" role="group" aria-label="Motivos mais comuns">
              <button
                v-for="s in SUGESTOES[sentido]"
                :key="s"
                type="button"
                class="min-h-9 rounded-full border border-borda-forte bg-superficie px-3 py-1 text-sm font-semibold text-texto-2 transition-colors hover:bg-neutro"
                @click="descricao = s"
              >{{ s }}</button>
            </div>
          </div>

          <div class="flex flex-col gap-1 rounded-xl bg-superficie-2 px-4 py-3" aria-live="polite">
            <p class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
              <span class="text-[0.95rem] font-semibold text-texto-2">{{ usando ? 'Você vai usar' : 'Você vai somar' }}</span>
              <span v-if="segundos" class="text-2xl font-extrabold tracking-tight" :class="corDoSaldo(segundos)">{{ saldo(segundos) }}</span>
              <span v-else class="text-[0.95rem] text-texto-3">informe quantas horas</span>
            </p>
            <p v-if="store.ciclo" class="flex flex-wrap items-baseline justify-between gap-x-4 text-sm text-texto-3">
              <span>Saldo do banco hoje</span>
              <b :class="corDoSaldo(store.ciclo.saldoSegundos)">{{ saldoComSentido(store.ciclo.saldoSegundos) }}</b>
            </p>
          </div>

          <p v-if="erroServidor" class="aviso-erro" role="alert">{{ erroServidor }}</p>
        </div>

        <footer class="sticky bottom-0 flex flex-wrap justify-end gap-2 border-t border-borda bg-superficie px-5 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <button type="button" class="botao-secundario min-h-11" :disabled="store.salvando" @click="fechar">Cancelar</button>
          <button type="submit" class="botao-primario min-h-11 grow sm:grow-0" :disabled="store.salvando || (tentou && !valido)">
            {{ store.salvando ? 'Lançando…' : usando ? 'Usar do banco' : 'Somar ao banco' }}
          </button>
        </footer>
      </form>
    </div>
  </Teleport>
</template>
