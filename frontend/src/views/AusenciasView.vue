<script setup>
import { computed, onMounted, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { dataBR, diaSemanaCurto } from '@/utils/tempo'

/**
 * Férias, atestados, licenças, folgas, abonos e feriados: os dias úteis ficam com jornada base zero
 * (não geram débito) e saem das pendências do painel. Dias já registrados são recalculados.
 */
const auth = useAuthStore()
const ponto = usePontoStore()

const TIPOS = [
  { valor: 'FERIAS', rotulo: 'Férias' },
  { valor: 'FOLGA', rotulo: 'Folga (ex.: aniversário)' },
  { valor: 'ATESTADO', rotulo: 'Atestado' },
  { valor: 'LICENCA', rotulo: 'Licença' },
  { valor: 'ABONO', rotulo: 'Outra justificativa (abono)' },
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
  if (f.tipo === 'ABONO' && !f.descricao.trim()) return 'Informe a justificativa do abono (ex.: doação de sangue).'
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

const corTipo = {
  FERIAS: 'bg-credito/10 text-credito',
  FOLGA: 'bg-tinta/10 text-tinta',
  ATESTADO: 'bg-carimbo/10 text-carimbo',
  LICENCA: 'bg-amber-500/15 text-amber-800',
  ABONO: 'bg-tinta/5 text-tinta-suave',
}

// ---------------------------------------------------------------- feriados
const ABRANGENCIAS = [
  { valor: 'MUNICIPAL', rotulo: 'Municipal' },
  { valor: 'ESTADUAL', rotulo: 'Estadual' },
  { valor: 'NACIONAL', rotulo: 'Nacional' },
  { valor: 'EMPRESA', rotulo: 'Empresa / ponto facultativo' },
]
const anoFeriados = ref(anoAtual)
const feriados = ref([])
const carregandoFeriados = ref(false)
const formFeriado = ref({ data: '', descricao: '', abrangencia: 'MUNICIPAL' })
const tentouFeriado = ref(false)
const salvandoFeriado = ref(false)
const confirmandoFeriado = ref(null)

async function carregarFeriados() {
  carregandoFeriados.value = true
  try {
    feriados.value = await pontoApi.feriados(`${anoFeriados.value}-01-01`, `${anoFeriados.value}-12-31`)
  } catch (e) {
    erro.value = e.message
  } finally {
    carregandoFeriados.value = false
  }
}
onMounted(carregarFeriados)

function mudarAnoFeriados(delta) {
  anoFeriados.value += delta
  carregarFeriados()
}

const erroFeriado = computed(() => {
  if (!formFeriado.value.data) return 'Informe a data.'
  if (!formFeriado.value.descricao.trim()) return 'Informe o nome do feriado (ex.: Corpus Christi).'
  return null
})

async function cadastrarFeriado() {
  tentouFeriado.value = true
  if (erroFeriado.value) return
  salvandoFeriado.value = true
  erro.value = ''
  try {
    const novo = await pontoApi.cadastrarFeriado({ ...formFeriado.value, descricao: formFeriado.value.descricao.trim() })
    mostrarAviso(`${novo.descricao} (${dataBR(novo.data)}) cadastrado: o dia não gera débito.`)
    const ano = Number(novo.data.slice(0, 4))
    formFeriado.value = { data: '', descricao: '', abrangencia: formFeriado.value.abrangencia }
    tentouFeriado.value = false
    if (ano !== anoFeriados.value) anoFeriados.value = ano
    await carregarFeriados()
    ponto.recarregarMes().catch(() => {})
  } catch (e) {
    erro.value = e.message
  } finally {
    salvandoFeriado.value = false
  }
}

async function excluirFeriado(f) {
  if (confirmandoFeriado.value !== f.data) {
    confirmandoFeriado.value = f.data
    setTimeout(() => confirmandoFeriado.value === f.data && (confirmandoFeriado.value = null), 4000)
    return
  }
  confirmandoFeriado.value = null
  try {
    await pontoApi.excluirFeriado(f.data)
    mostrarAviso(`${f.descricao} (${dataBR(f.data)}) removido: o dia voltou a ser útil.`)
    await carregarFeriados()
    ponto.recarregarMes().catch(() => {})
  } catch (e) {
    erro.value = e.message
  }
}
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">Exceções de calendário</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Folgas e feriados</h1>
      <p v-if="!auth.vendoOsProprios" class="mt-1 font-sans text-lg font-semibold">{{ auth.pessoaEmTela?.nome }}</p>
      <p class="mt-2 max-w-2xl text-sm text-tinta-suave">
        Nos dias de feriado, férias, atestado, licença, folga ou outra justificativa a jornada base é zero: não há débito
        e o dia não aparece como pendente. Se trabalhar num desses dias, o tempo vira crédito. Também dá para marcar pelo
        próprio dia no Painel (ícone de calendário). Para <b>descontar</b> horas do banco (folga compensada), use
        <b>Lançar no banco</b> no Painel. Os períodos do relatório do RH também podem ser trazidos pela
        <RouterLink :to="{ name: 'conciliacao' }" class="font-semibold text-tinta underline underline-offset-4">Conciliação</RouterLink>.
      </p>
    </header>

    <p v-if="erro" role="alert" class="cartao mt-4 border-carimbo/50 px-5 py-3 text-sm text-carimbo">{{ erro }}</p>

    <!-- Feriados -->
    <section class="cartao mt-8 overflow-hidden" aria-label="Feriados">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-linha px-5 py-3">
        <h2 class="rotulo">Feriados</h2>
        <div class="flex items-center gap-2">
          <button type="button" class="botao-secundario px-2.5! py-1! text-xs" aria-label="Ano anterior" @click="mudarAnoFeriados(-1)">‹</button>
          <span class="carimbo font-semibold">{{ anoFeriados }}</span>
          <button type="button" class="botao-secundario px-2.5! py-1! text-xs" aria-label="Próximo ano" @click="mudarAnoFeriados(1)">›</button>
        </div>
      </div>

      <p v-if="auth.podeEscrever && !auth.ehAdmin" class="border-b border-dashed border-linha px-5 py-3 text-sm text-tinta-suave">
        Feriados valem para todos e são cadastrados pelo administrador.
      </p>
      <form
        v-if="auth.podeEscrever && auth.ehAdmin"
        class="grid gap-3 border-b border-dashed border-linha px-5 py-4 sm:grid-cols-[10rem_1fr_12rem_auto]"
        novalidate
        @submit.prevent="cadastrarFeriado"
      >
        <label class="flex flex-col">
          <span class="rotulo">Data</span>
          <input v-model="formFeriado.data" type="date" class="campo mt-1" />
        </label>
        <label class="flex flex-col">
          <span class="rotulo">Nome</span>
          <input v-model="formFeriado.descricao" type="text" maxlength="120" class="campo mt-1 font-sans" placeholder="Ex.: Corpus Christi" />
        </label>
        <label class="flex flex-col">
          <span class="rotulo">Abrangência</span>
          <select v-model="formFeriado.abrangencia" class="campo mt-1 font-sans">
            <option v-for="a in ABRANGENCIAS" :key="a.valor" :value="a.valor">{{ a.rotulo }}</option>
          </select>
        </label>
        <div class="flex items-end">
          <button type="submit" class="botao-primario w-full" :disabled="salvandoFeriado">{{ salvandoFeriado ? 'Salvando…' : 'Cadastrar' }}</button>
        </div>
        <p v-if="tentouFeriado && erroFeriado" class="text-sm text-carimbo sm:col-span-4">{{ erroFeriado }}</p>
      </form>

      <p v-if="carregandoFeriados && !feriados.length" class="px-5 py-6 text-center text-sm text-tinta-suave">Carregando…</p>
      <p v-else-if="!feriados.length" class="px-5 py-6 text-center text-sm text-tinta-suave">Nenhum feriado cadastrado em {{ anoFeriados }}.</p>
      <ul class="divide-y divide-linha/70">
        <li v-for="f in feriados" :key="f.data" class="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-2.5">
          <span class="carimbo w-28 font-semibold">{{ diaSemanaCurto(f.data) }} {{ dataBR(f.data).slice(0, 5) }}</span>
          <span class="min-w-0 flex-1">{{ f.descricao }}</span>
          <span class="rounded-[2px] bg-carimbo/10 px-1.5 py-0.5 text-[0.65rem] font-bold uppercase tracking-wide text-carimbo">{{ f.abrangenciaRotulo }}</span>
          <button
            v-if="auth.podeEscrever && auth.ehAdmin"
            type="button"
            class="rounded-[3px] px-2 py-1 text-xs font-semibold transition"
            :class="confirmandoFeriado === f.data ? 'bg-carimbo text-cartao' : 'text-tinta-suave hover:bg-papel-escuro hover:text-carimbo'"
            @click="excluirFeriado(f)"
          >{{ confirmandoFeriado === f.data ? 'Confirmar remoção?' : 'Remover' }}</button>
        </li>
      </ul>
    </section>

    <h2 class="rotulo mt-10">Férias, folgas, atestados e outras justificativas</h2>

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
        <span class="rotulo">{{ form.tipo === 'ABONO' ? 'Justificativa' : 'Descrição (opcional)' }}</span>
        <input
          v-model="form.descricao"
          type="text"
          maxlength="200"
          class="campo mt-1 font-sans"
          :placeholder="form.tipo === 'ABONO' ? 'Justificativa (obrigatória): ex.: doação de sangue' : 'Ex.: Férias 2026 (1º período)'"
        />
      </label>
      <div class="flex items-end">
        <button type="submit" class="botao-primario w-full" :disabled="salvando">
          {{ salvando ? 'Salvando…' : diasCorridos ? `Cadastrar ${diasCorridos} dia(s)` : 'Cadastrar' }}
        </button>
      </div>
      <p v-if="tentou && erroForm" class="text-sm text-carimbo sm:col-span-3">{{ erroForm }}</p>
    </form>


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
