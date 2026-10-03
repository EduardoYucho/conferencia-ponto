<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { dataBR, diaSemanaCurto } from '@/utils/tempo'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'

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
/** A lista de períodos já chegou (antes disso não dá para dizer "nenhuma ausência": é desconhecido). */
const listaCarregada = ref(false)
/** Falha ao carregar os períodos (aparece na própria lista, com "Tentar de novo"). */
const erroLista = ref(null)
/** O servidor recusou o cadastro (aparece dentro do formulário). */
const erroCadastro = ref('')
/** A remoção de uma linha falhou: { chave, mensagem }, mostrado na própria linha. */
const falhaNaLinha = ref(null)
const aviso = ref('')
const salvando = ref(false)
const confirmando = ref(null)
const form = ref({ dataInicio: '', dataFim: '', tipo: 'FERIAS', descricao: '' })
const tentou = ref(false)
let timerAviso = null
let timerConfirmacao = null
let timerConfirmacaoFeriado = null
onBeforeUnmount(() => {
  clearTimeout(timerAviso)
  clearTimeout(timerConfirmacao)
  clearTimeout(timerConfirmacaoFeriado)
})

/** No início de cada ação: o erro de uma ação anterior não fica na tela depois de outra tentativa. */
function limparErrosDeAcao() {
  erroCadastro.value = ''
  erroCadastroFeriado.value = ''
  falhaNaLinha.value = null
}

const anoAtual = new Date().getFullYear()
const intervalo = { inicio: `${anoAtual - 2}-01-01`, fim: `${anoAtual + 1}-12-31` }

async function carregar() {
  carregando.value = true
  erroLista.value = null
  try {
    lista.value = (await pontoApi.ausencias(intervalo.inicio, intervalo.fim)).slice().reverse()
    listaCarregada.value = true
  } catch (e) {
    erroLista.value = e
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
  limparErrosDeAcao()
  if (erroForm.value) return
  salvando.value = true
  try {
    let nova
    try {
      nova = await pontoApi.cadastrarAusencia({ ...form.value, descricao: form.value.descricao.trim() || null })
    } catch (e) {
      erroCadastro.value = mensagemDe(e)
      return
    }
    // cadastrou: o que vem depois (recarregar a lista, atualizar os saldos) não pode parecer falha do cadastro
    mostrarAviso(`${nova?.tipoRotulo ?? 'Ausência'} de ${dataBR(nova?.dataInicio)} a ${dataBR(nova?.dataFim)} cadastrada: os dias úteis não geram débito.`)
    form.value = { dataInicio: '', dataFim: '', tipo: form.value.tipo, descricao: '' }
    tentou.value = false
    await carregar()
    ponto.atualizarSaldos() // se falhar, o aviso geral da tela oferece "Atualizar agora"
  } finally {
    salvando.value = false
  }
}

async function excluir(a) {
  limparErrosDeAcao()
  if (confirmando.value !== a.id) {
    confirmando.value = a.id
    clearTimeout(timerConfirmacao)
    timerConfirmacao = setTimeout(() => (confirmando.value = null), 4000)
    return
  }
  clearTimeout(timerConfirmacao)
  confirmando.value = null
  try {
    await pontoApi.excluirAusencia(a.id)
  } catch (e) {
    falhaNaLinha.value = { chave: `ausencia-${a.id}`, mensagem: mensagemDe(e) }
    return
  }
  mostrarAviso(`${a.tipoRotulo} de ${dataBR(a.dataInicio)} removida: os dias voltam a ser úteis.`)
  await carregar()
  ponto.atualizarSaldos() // se falhar, o aviso geral da tela oferece "Atualizar agora"
}

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
/** Os feriados do ano em tela já chegaram (antes disso não dá para dizer "nenhum feriado"). */
const feriadosCarregados = ref(false)
const erroFeriados = ref(null)
const erroCadastroFeriado = ref('')
let pedidoFeriados = 0
const formFeriado = ref({ data: '', descricao: '', abrangencia: 'MUNICIPAL' })
const tentouFeriado = ref(false)
const salvandoFeriado = ref(false)
const confirmandoFeriado = ref(null)

async function carregarFeriados() {
  // trocando de ano depressa, as respostas podem chegar fora de ordem: só a do último pedido entra na tela
  const pedido = ++pedidoFeriados
  carregandoFeriados.value = true
  erroFeriados.value = null
  try {
    const doAno = await pontoApi.feriados(`${anoFeriados.value}-01-01`, `${anoFeriados.value}-12-31`)
    if (pedido !== pedidoFeriados) return
    feriados.value = doAno
    feriadosCarregados.value = true
  } catch (e) {
    if (pedido === pedidoFeriados) erroFeriados.value = e
  } finally {
    if (pedido === pedidoFeriados) carregandoFeriados.value = false
  }
}
onMounted(carregarFeriados)

/** Troca o ano em tela: os feriados do ano anterior não ficam sob o ano novo. */
function mostrarFeriadosDe(ano) {
  anoFeriados.value = ano
  feriados.value = []
  feriadosCarregados.value = false
  falhaNaLinha.value = null
  return carregarFeriados()
}

const mudarAnoFeriados = (delta) => mostrarFeriadosDe(anoFeriados.value + delta)

const erroFeriado = computed(() => {
  if (!formFeriado.value.data) return 'Informe a data.'
  if (!formFeriado.value.descricao.trim()) return 'Informe o nome do feriado (ex.: Corpus Christi).'
  return null
})

async function cadastrarFeriado() {
  tentouFeriado.value = true
  limparErrosDeAcao()
  if (erroFeriado.value) return
  salvandoFeriado.value = true
  try {
    const enviado = { ...formFeriado.value, descricao: formFeriado.value.descricao.trim() }
    let novo
    try {
      novo = await pontoApi.cadastrarFeriado(enviado)
    } catch (e) {
      erroCadastroFeriado.value = mensagemDe(e)
      return
    }
    // cadastrou: o que vem depois (recarregar a lista e o mês) não pode parecer falha do cadastro
    const data = novo?.data ?? enviado.data
    mostrarAviso(`${novo?.descricao ?? enviado.descricao} (${dataBR(data)}) cadastrado: o dia não gera débito.`)
    const ano = Number(data.slice(0, 4))
    formFeriado.value = { data: '', descricao: '', abrangencia: formFeriado.value.abrangencia }
    tentouFeriado.value = false
    if (ano !== anoFeriados.value) await mostrarFeriadosDe(ano)
    else await carregarFeriados()
    ponto.recarregarMes().catch(() => {})
  } finally {
    salvandoFeriado.value = false
  }
}

async function excluirFeriado(f) {
  limparErrosDeAcao()
  if (confirmandoFeriado.value !== f.data) {
    confirmandoFeriado.value = f.data
    clearTimeout(timerConfirmacaoFeriado)
    timerConfirmacaoFeriado = setTimeout(() => (confirmandoFeriado.value = null), 4000)
    return
  }
  clearTimeout(timerConfirmacaoFeriado)
  confirmandoFeriado.value = null
  try {
    await pontoApi.excluirFeriado(f.data)
  } catch (e) {
    falhaNaLinha.value = { chave: `feriado-${f.data}`, mensagem: mensagemDe(e) }
    return
  }
  mostrarAviso(`${f.descricao} (${dataBR(f.data)}) removido: o dia voltou a ser útil.`)
  await carregarFeriados()
  ponto.recarregarMes().catch(() => {})
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
        <p v-if="erroCadastroFeriado" role="alert" class="text-sm text-carimbo sm:col-span-4">{{ erroCadastroFeriado }}</p>
      </form>

      <!-- Carregando, falhou (com "Tentar de novo") ou sem feriados no ano: três situações diferentes -->
      <div v-if="erroFeriados || !feriados.length" class="px-5" :class="{ 'py-3': erroFeriados }">
        <EstadoDaTela
          :carregando="carregandoFeriados || (!feriadosCarregados && !erroFeriados)"
          :erro="erroFeriados"
          vazio
          :vazio-texto="`Nenhum feriado cadastrado em ${anoFeriados}.`"
          @tentar="carregarFeriados"
        />
      </div>
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
          <p v-if="falhaNaLinha?.chave === `feriado-${f.data}`" role="alert" class="w-full text-sm text-carimbo">{{ falhaNaLinha.mensagem }}</p>
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
      <p v-if="erroCadastro" role="alert" class="text-sm text-carimbo sm:col-span-3">{{ erroCadastro }}</p>
    </form>


    <section class="cartao mt-6 overflow-hidden" aria-label="Períodos cadastrados">
      <h2 class="rotulo border-b border-linha px-5 py-3">Períodos cadastrados</h2>
      <!-- Carregando, falhou (com "Tentar de novo") ou nenhum período: três situações diferentes -->
      <div v-if="erroLista || !lista.length" class="px-5" :class="{ 'py-3': erroLista }">
        <EstadoDaTela
          :carregando="carregando || (!listaCarregada && !erroLista)"
          :erro="erroLista"
          vazio
          vazio-texto="Nenhuma ausência cadastrada."
          @tentar="carregar"
        />
      </div>
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
          <p v-if="falhaNaLinha?.chave === `ausencia-${a.id}`" role="alert" class="w-full text-sm text-carimbo">{{ falhaNaLinha.mensagem }}</p>
        </li>
      </ul>
    </section>

    <Transition enter-active-class="transition duration-200" enter-from-class="translate-y-3 opacity-0" leave-active-class="transition duration-150" leave-to-class="opacity-0">
      <div v-if="aviso" role="status" class="fixed inset-x-4 bottom-4 z-40 mx-auto max-w-md rounded-[3px] bg-tinta px-4 py-3 text-sm font-medium text-cartao shadow-lg sm:inset-x-auto sm:right-6 sm:bottom-6">{{ aviso }}</div>
    </Transition>
  </div>
</template>
