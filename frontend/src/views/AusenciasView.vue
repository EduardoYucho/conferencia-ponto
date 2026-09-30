<script setup>
import { computed, onMounted, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { dataBR, diaSemanaCurto } from '@/utils/tempo'

/**
 * Férias, atestados, licenças e folgas: os dias úteis do período ficam com jornada base zero
 * (não geram débito) e saem das pendências do painel. Dias já registrados são recalculados.
 */
const auth = useAuthStore()
const ponto = usePontoStore()

const TIPOS = [
  { valor: 'FERIAS', rotulo: 'Férias' },
  { valor: 'FOLGA', rotulo: 'Folga (ex.: aniversário)' },
  { valor: 'ATESTADO', rotulo: 'Atestado' },
  { valor: 'LICENCA', rotulo: 'Licença' },
]

const lista = ref([])
const carregando = ref(false)
const erro = ref('')
const aviso = ref('')
const salvando = ref(false)
const confirmando = ref(null)
const form = ref({ dataInicio: '', dataFim: '', tipo: 'FERIAS', descricao: '' })
const tentou = ref(false)

const anoAtual = new Date().getFullYear()
const intervalo = { inicio: `${anoAtual - 2}-01-01`, fim: `${anoAtual + 1}-12-31` }

async function carregar() {
  carregando.value = true
  erro.value = ''
  try {
    lista.value = (await pontoApi.ausencias(intervalo.inicio, intervalo.fim)).slice().reverse()
  } catch (e) {
    erro.value = e.message
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)

const erroForm = computed(() => {
  const f = form.value
  if (!f.dataInicio || !f.dataFim) return 'Informe o início e o fim.'
  if (f.dataFim < f.dataInicio) return 'O fim deve ser igual ou posterior ao início.'
  return null
})

const diasCorridos = computed(() => {
  const f = form.value
  if (!f.dataInicio || !f.dataFim || f.dataFim < f.dataInicio) return 0
  return Math.round((new Date(f.dataFim) - new Date(f.dataInicio)) / 86_400_000) + 1
})

function aoMudarInicio() {
  if (!form.value.dataFim || form.value.dataFim < form.value.dataInicio) form.value.dataFim = form.value.dataInicio
}

async function cadastrar() {
  tentou.value = true
  if (erroForm.value) return
  salvando.value = true
  erro.value = ''
  try {
    const nova = await pontoApi.cadastrarAusencia({ ...form.value, descricao: form.value.descricao.trim() || null })
    mostrarAviso(`${nova.tipoRotulo} de ${dataBR(nova.dataInicio)} a ${dataBR(nova.dataFim)} cadastrada: os dias úteis não geram débito.`)
    form.value = { dataInicio: '', dataFim: '', tipo: form.value.tipo, descricao: '' }
    tentou.value = false
    await carregar()
    ponto.atualizarSaldos()
  } catch (e) {
    erro.value = e.message
  } finally {
    salvando.value = false
  }
}

async function excluir(a) {
  if (confirmando.value !== a.id) {
    confirmando.value = a.id
    setTimeout(() => confirmando.value === a.id && (confirmando.value = null), 4000)
    return
  }
  confirmando.value = null
  try {
    await pontoApi.excluirAusencia(a.id)
    mostrarAviso(`${a.tipoRotulo} de ${dataBR(a.dataInicio)} removida: os dias voltam a ser úteis.`)
    await carregar()
    ponto.atualizarSaldos()
  } catch (e) {
    erro.value = e.message
  }
}

let timerAviso = null
function mostrarAviso(texto) {
  aviso.value = texto
  clearTimeout(timerAviso)
  timerAviso = setTimeout(() => (aviso.value = ''), 5000)
}

const corTipo = { FERIAS: 'bg-credito/10 text-credito', FOLGA: 'bg-tinta/10 text-tinta', ATESTADO: 'bg-carimbo/10 text-carimbo', LICENCA: 'bg-amber-500/15 text-amber-800' }
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">Exceções de calendário</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Férias e folgas</h1>
      <p class="mt-2 max-w-2xl text-sm text-tinta-suave">
        Nos dias úteis de férias, atestados, licenças e folgas a jornada base é zero: não há débito e o dia não aparece
        como pendente. Se trabalhar num desses dias, o tempo vira crédito. Os períodos do relatório do RH também podem
        ser trazidos pela <RouterLink :to="{ name: 'conciliacao' }" class="font-semibold text-tinta underline underline-offset-4">Conciliação</RouterLink>.
      </p>
    </header>

    <form v-if="auth.podeEscrever" class="cartao mt-6 grid gap-4 px-5 py-5 sm:grid-cols-[1fr_1fr_1.2fr]" novalidate @submit.prevent="cadastrar">
      <label class="flex flex-col">
        <span class="rotulo">Início</span>
        <input v-model="form.dataInicio" type="date" class="campo mt-1" @change="aoMudarInicio" />
      </label>
      <label class="flex flex-col">
        <span class="rotulo">Fim</span>
        <input v-model="form.dataFim" type="date" class="campo mt-1" :min="form.dataInicio || undefined" />
      </label>
      <label class="flex flex-col">
        <span class="rotulo">Tipo</span>
        <select v-model="form.tipo" class="campo mt-1 font-sans">
          <option v-for="t in TIPOS" :key="t.valor" :value="t.valor">{{ t.rotulo }}</option>
        </select>
      </label>
      <label class="flex flex-col sm:col-span-2">
        <span class="rotulo">Descrição (opcional)</span>
        <input v-model="form.descricao" type="text" maxlength="200" class="campo mt-1 font-sans" placeholder="Ex.: Férias 2026 (1º período)" />
      </label>
      <div class="flex items-end">
        <button type="submit" class="botao-primario w-full" :disabled="salvando">
          {{ salvando ? 'Salvando…' : diasCorridos ? `Cadastrar ${diasCorridos} dia(s)` : 'Cadastrar' }}
        </button>
      </div>
      <p v-if="tentou && erroForm" class="text-sm text-carimbo sm:col-span-3">{{ erroForm }}</p>
    </form>

    <p v-if="erro" role="alert" class="cartao mt-4 border-carimbo/50 px-5 py-3 text-sm text-carimbo">{{ erro }}</p>

    <section class="cartao mt-6 overflow-hidden" aria-label="Períodos cadastrados">
      <h2 class="rotulo border-b border-linha px-5 py-3">Períodos cadastrados</h2>
      <p v-if="carregando && !lista.length" class="px-5 py-8 text-center text-sm text-tinta-suave">Carregando…</p>
      <p v-else-if="!lista.length" class="px-5 py-8 text-center text-sm text-tinta-suave">Nenhuma ausência cadastrada.</p>
      <ul class="divide-y divide-linha/70">
        <li v-for="a in lista" :key="a.id" class="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3">
          <span class="rounded-[2px] px-1.5 py-0.5 text-[0.68rem] font-bold uppercase tracking-wide" :class="corTipo[a.tipo]">{{ a.tipoRotulo }}</span>
          <span class="carimbo font-semibold">
            {{ diaSemanaCurto(a.dataInicio) }} {{ dataBR(a.dataInicio) }}
            <template v-if="a.dataFim !== a.dataInicio"> → {{ diaSemanaCurto(a.dataFim) }} {{ dataBR(a.dataFim) }}</template>
          </span>
          <span class="text-sm text-tinta-suave">{{ a.dias }} dia(s)<template v-if="a.descricao"> · {{ a.descricao }}</template></span>
          <span class="text-xs text-tinta-apagada">por {{ a.criadoPor }}</span>
          <button
            v-if="auth.podeEscrever"
            type="button"
            class="ml-auto rounded-[3px] px-2 py-1 text-xs font-semibold transition"
            :class="confirmando === a.id ? 'bg-carimbo text-cartao' : 'text-tinta-suave hover:bg-papel-escuro hover:text-carimbo'"
            @click="excluir(a)"
          >{{ confirmando === a.id ? 'Confirmar remoção?' : 'Remover' }}</button>
        </li>
      </ul>
    </section>

    <Transition enter-active-class="transition duration-200" enter-from-class="translate-y-3 opacity-0" leave-active-class="transition duration-150" leave-to-class="opacity-0">
      <div v-if="aviso" role="status" class="fixed inset-x-4 bottom-4 z-40 mx-auto max-w-md rounded-[3px] bg-tinta px-4 py-3 text-sm font-medium text-cartao shadow-lg sm:inset-x-auto sm:right-6 sm:bottom-6">{{ aviso }}</div>
    </Transition>
  </div>
</template>
