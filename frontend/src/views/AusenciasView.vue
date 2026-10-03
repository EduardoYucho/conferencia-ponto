<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import { dataBR, diaMes } from '@/utils/horas'
import { diaSemanaCurto } from '@/utils/tempo'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'

/**
 * Folgas e feriados: os dias em que a pessoa não trabalha (folga, férias, atestado, licença, outra justificativa)
 * e os feriados, que valem para todos e são cadastrados só pelo administrador.
 *
 * A tela só cadastra, lista e remove: o efeito no saldo e no banco de horas é do servidor. Depois de gravar, avisa
 * as outras telas (`ponto.sinalizarMudanca`) e pede os saldos de novo.
 */
const auth = useAuthStore()
const ponto = usePontoStore()

const meus = computed(() => auth.vendoOsProprios && auth.ehTitular)
const podeFeriado = computed(() => auth.podeEscrever && auth.ehAdmin)

const TIPOS = [
  { valor: 'FOLGA', rotulo: 'Folga', exemplo: 'Ex.: folga de aniversário' },
  { valor: 'FERIAS', rotulo: 'Férias', exemplo: 'Ex.: 1º período de férias' },
  { valor: 'ATESTADO', rotulo: 'Atestado', exemplo: 'Ex.: atestado médico' },
  { valor: 'LICENCA', rotulo: 'Licença', exemplo: 'Ex.: licença-paternidade' },
  { valor: 'ABONO', rotulo: 'Outra justificativa', exemplo: 'Ex.: doação de sangue' },
]
const ABRANGENCIAS = [
  { valor: 'MUNICIPAL', rotulo: 'Municipal' },
  { valor: 'ESTADUAL', rotulo: 'Estadual' },
  { valor: 'NACIONAL', rotulo: 'Nacional' },
  { valor: 'EMPRESA', rotulo: 'Empresa / ponto facultativo' },
]

const anoAtual = Number(ponto.hoje.slice(0, 4))
const semana = (iso) => diaSemanaCurto(iso).toLowerCase()
const quantosDias = (n) => (n === 1 ? '1 dia' : `${n} dias`)
/** "05/06/2026" ou "01/07/2026 a 15/07/2026" */
const periodo = (a) => (a.dataInicio === a.dataFim ? dataBR(a.dataInicio) : `${dataBR(a.dataInicio)} a ${dataBR(a.dataFim)}`)

// ------------------------------------------------------------------ folgas, férias e atestados da pessoa
const lista = ref([])
const carregando = ref(false)
/** A lista já chegou (antes disso não dá para dizer "nenhuma folga": ainda não se sabe). */
const listaCarregada = ref(false)
const erroLista = ref(null)
let pedidoLista = 0
/** O que a tela mostra: do ano retrasado ao fim do ano que vem. */
const intervalo = { inicio: `${anoAtual - 2}-01-01`, fim: `${anoAtual + 1}-12-31` }

async function carregar({ silencioso = false } = {}) {
  const pedido = ++pedidoLista
  if (!silencioso) carregando.value = true
  try {
    const dados = await pontoApi.ausencias(intervalo.inicio, intervalo.fim)
    if (pedido !== pedidoLista) return
    lista.value = dados.slice().reverse() // o mais recente primeiro
    listaCarregada.value = true
    erroLista.value = null
  } catch (e) {
    if (pedido === pedidoLista && (!silencioso || !listaCarregada.value)) erroLista.value = e
  } finally {
    if (pedido === pedidoLista) carregando.value = false
  }
}

// ------------------------------------------------------------------ feriados (de todos), um ano por vez
const anoFeriados = ref(anoAtual)
const feriados = ref([])
const carregandoFeriados = ref(false)
/** Os feriados do ano em tela já chegaram (antes disso não dá para dizer "nenhum feriado"). */
const feriadosCarregados = ref(false)
const erroFeriados = ref(null)
let pedidoFeriados = 0

async function carregarFeriados({ silencioso = false } = {}) {
  // trocando de ano depressa, as respostas podem chegar fora de ordem: só a do último pedido entra na tela
  const pedido = ++pedidoFeriados
  if (!silencioso) carregandoFeriados.value = true
  try {
    const doAno = await pontoApi.feriados(`${anoFeriados.value}-01-01`, `${anoFeriados.value}-12-31`)
    if (pedido !== pedidoFeriados) return
    feriados.value = doAno
    feriadosCarregados.value = true
    erroFeriados.value = null
  } catch (e) {
    if (pedido === pedidoFeriados && (!silencioso || !feriadosCarregados.value)) erroFeriados.value = e
  } finally {
    if (pedido === pedidoFeriados) carregandoFeriados.value = false
  }
}

/** Troca o ano em tela: os feriados do ano anterior não ficam sob o ano novo. */
function mostrarFeriadosDe(ano) {
  anoFeriados.value = ano
  feriados.value = []
  feriadosCarregados.value = false
  erroFeriados.value = null
  return carregarFeriados()
}
const mudarAnoFeriados = (delta) => mostrarFeriadosDe(anoFeriados.value + delta)

onMounted(() => {
  carregar()
  carregarFeriados()
})

// ------------------------------------------------------------------ avisar as outras telas e ouvir o que muda fora daqui
/** Última mudança que esta tela já tem (a que ela mesma avisou não precisa de nova consulta). */
let mudancaVista = ponto.mudouEm
/** Depois de gravar ou remover: Início, Meu ponto e Banco de horas recarregam; os saldos são pedidos de novo. */
function avisarQueMudou(data) {
  ponto.sinalizarMudanca({ tipo: 'calendario', data })
  mudancaVista = ponto.mudouEm
  ponto.atualizarSaldos() // se falhar, o aviso geral da tela oferece "Atualizar agora"
}
function relerEmSilencio() {
  carregar({ silencioso: true })
  carregarFeriados({ silencioso: true })
}
// uma folga marcada em outra tela ou em outra aba: as listas são relidas
watch(() => ponto.mudouEm, (mudou) => {
  if (mudou === mudancaVista) return
  mudancaVista = mudou
  relerEmSilencio()
})
// a conexão voltou depois de uma queda: o que mudou nesse meio-tempo não chegou por evento
watch(() => ponto.reconectouEm, (momento) => momento && relerEmSilencio())

// ------------------------------------------------------------------ janelas (uma por vez)
/** 'periodo' | 'feriado' | null */
const janela = ref(null)
const primeiroCampo = ref(null)
/** Botão que abriu a janela: o foco volta para ele quando ela fecha. */
let abertaPor = null

async function abrirJanela(qual, evento) {
  abertaPor = evento?.currentTarget ?? null
  janela.value = qual
  await nextTick()
  primeiroCampo.value?.focus()
}
function fecharJanela() {
  if (salvando.value || salvandoFeriado.value) return
  janela.value = null
  abertaPor?.focus?.()
}

// ------------------------------------------------------------------ marcar folga, férias, atestado...
const form = ref({ dataInicio: '', dataFim: '', tipo: 'FOLGA', descricao: '' })
const tentou = ref(false)
const salvando = ref(false)
/** O servidor recusou o cadastro (aparece dentro do formulário). */
const erroCadastro = ref('')

const tipoEscolhido = computed(() => TIPOS.find((t) => t.valor === form.value.tipo))
const justificativaObrigatoria = computed(() => form.value.tipo === 'ABONO')

const erroForm = computed(() => {
  const f = form.value
  if (!f.dataInicio || !f.dataFim) return 'Informe o primeiro e o último dia.'
  if (f.dataFim < f.dataInicio) return 'O último dia precisa ser igual ou depois do primeiro.'
  if (justificativaObrigatoria.value && !f.descricao.trim()) return 'Escreva a justificativa (ex.: doação de sangue).'
  return null
})

/** Quantos dias o período escolhido tem (só para o texto do botão). */
const diasCorridos = computed(() => {
  const f = form.value
  if (!f.dataInicio || !f.dataFim || f.dataFim < f.dataInicio) return 0
  return Math.round((new Date(f.dataFim) - new Date(f.dataInicio)) / 86_400_000) + 1
})

function aoMudarInicio() {
  if (!form.value.dataFim || form.value.dataFim < form.value.dataInicio) form.value.dataFim = form.value.dataInicio
}

function abrirPeriodo(evento) {
  tentou.value = false
  erroCadastro.value = ''
  abrirJanela('periodo', evento)
}

async function cadastrar() {
  tentou.value = true
  erroCadastro.value = ''
  if (erroForm.value) return
  salvando.value = true
  let nova
  try {
    nova = await pontoApi.cadastrarAusencia({ ...form.value, descricao: form.value.descricao.trim() || null })
  } catch (e) {
    erroCadastro.value = mensagemDe(e)
    return
  } finally {
    salvando.value = false
  }
  // cadastrou: o que vem depois (recarregar a lista, atualizar os saldos) não pode parecer falha do cadastro
  const rotulo = (nova?.tipoRotulo ?? tipoEscolhido.value?.rotulo ?? 'Período').toLowerCase()
  const marcado = { dataInicio: nova?.dataInicio ?? form.value.dataInicio, dataFim: nova?.dataFim ?? form.value.dataFim }
  form.value = { dataInicio: '', dataFim: '', tipo: form.value.tipo, descricao: '' }
  tentou.value = false
  fecharJanela()
  avisar(marcado.dataInicio === marcado.dataFim
    ? `Marcado: ${rotulo} em ${periodo(marcado)}. O dia não fica devendo horas.`
    : `Marcado: ${rotulo} de ${periodo(marcado)}. Esses dias não ficam devendo horas.`)
  await carregar({ silencioso: listaCarregada.value })
  avisarQueMudou(marcado.dataInicio)
}

// ------------------------------------------------------------------ cadastrar feriado (administrador)
const formFeriado = ref({ data: '', descricao: '', abrangencia: 'MUNICIPAL' })
const tentouFeriado = ref(false)
const salvandoFeriado = ref(false)
const erroCadastroFeriado = ref('')

const erroFeriado = computed(() => {
  if (!formFeriado.value.data) return 'Informe a data.'
  if (!formFeriado.value.descricao.trim()) return 'Informe o nome do feriado (ex.: Corpus Christi).'
  return null
})

function abrirFeriado(evento) {
  tentouFeriado.value = false
  erroCadastroFeriado.value = ''
  abrirJanela('feriado', evento)
}

async function cadastrarFeriado() {
  tentouFeriado.value = true
  erroCadastroFeriado.value = ''
  if (erroFeriado.value) return
  salvandoFeriado.value = true
  const enviado = { ...formFeriado.value, descricao: formFeriado.value.descricao.trim() }
  let novo
  try {
    novo = await pontoApi.cadastrarFeriado(enviado)
  } catch (e) {
    erroCadastroFeriado.value = mensagemDe(e)
    return
  } finally {
    salvandoFeriado.value = false
  }
  // cadastrou: o que vem depois (recarregar a lista, avisar as outras telas) não pode parecer falha do cadastro
  const data = novo?.data ?? enviado.data
  formFeriado.value = { data: '', descricao: '', abrangencia: formFeriado.value.abrangencia }
  tentouFeriado.value = false
  fecharJanela()
  avisar(`Feriado cadastrado: ${novo?.descricao ?? enviado.descricao} (${dataBR(data)}). O dia não fica devendo horas para ninguém.`)
  const ano = Number(data.slice(0, 4))
  if (ano !== anoFeriados.value) await mostrarFeriadosDe(ano) // o feriado novo aparece: a lista vai para o ano dele
  else await carregarFeriados({ silencioso: feriadosCarregados.value })
  avisarQueMudou(data)
}

// ------------------------------------------------------------------ remover (com confirmação)
/** O que está para ser removido: { tipo: 'periodo' | 'feriado', item, titulo, texto } */
const remocao = ref(null)
const removendo = ref(false)
const botaoCancelar = ref(null)

async function pedirRemocao(tipo, item, evento) {
  abertaPor = evento?.currentTarget ?? null
  remocao.value = tipo === 'feriado'
    ? {
        tipo,
        item,
        titulo: `Remover o feriado de ${dataBR(item.data)}?`,
        texto: `${item.descricao} deixa de ser feriado para todas as pessoas: o dia volta a contar como dia de trabalho.`,
      }
    : {
        tipo,
        item,
        titulo: `Remover ${item.tipoRotulo.toLowerCase()} de ${periodo(item)}?`,
        texto: item.dataInicio === item.dataFim
          ? 'O dia volta a contar como dia de trabalho. Se precisar, dá para marcar de novo depois.'
          : `Os ${quantosDias(item.dias)} do período voltam a contar como dias de trabalho. Se precisar, dá para marcar de novo depois.`,
      }
  await nextTick()
  botaoCancelar.value?.focus()
}
function cancelarRemocao() {
  if (removendo.value) return
  remocao.value = null
  abertaPor?.focus?.()
}

async function confirmarRemocao() {
  const { tipo, item } = remocao.value
  removendo.value = true
  try {
    if (tipo === 'feriado') await pontoApi.excluirFeriado(item.data)
    else await pontoApi.excluirAusencia(item.id)
  } catch (e) {
    remocao.value = null
    avisar(`Não foi possível remover. ${mensagemDe(e)}`, 'erro')
    return
  } finally {
    removendo.value = false
  }
  remocao.value = null
  if (tipo === 'feriado') {
    avisar(`Feriado removido: ${item.descricao} (${dataBR(item.data)}). O dia voltou a contar como dia de trabalho.`)
    await carregarFeriados({ silencioso: true })
    avisarQueMudou(item.data)
  } else {
    avisar(`Removido: ${item.tipoRotulo.toLowerCase()} de ${periodo(item)}. ${item.dataInicio === item.dataFim ? 'O dia voltou' : 'Os dias voltaram'} a contar como de trabalho.`)
    await carregar({ silencioso: true })
    avisarQueMudou(item.dataInicio)
  }
}

function aoTeclar(evento) {
  if (evento.key !== 'Escape') return
  if (remocao.value) cancelarRemocao()
  else if (janela.value) fecharJanela()
}
window.addEventListener('keydown', aoTeclar)
onBeforeUnmount(() => {
  window.removeEventListener('keydown', aoTeclar)
  // respostas que ainda estão a caminho não entram numa tela que já saiu
  pedidoLista++
  pedidoFeriados++
})
</script>

<template>
  <main class="pagina">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div class="min-w-0">
        <h1 class="titulo-pagina">Folgas e feriados<template v-if="!meus && auth.pessoaEmTela"> de {{ auth.pessoaEmTela.nome }}</template></h1>
        <p class="subtitulo-pagina">
          Os dias em que {{ meus ? 'você não trabalha e não fica' : 'a pessoa não trabalha e não fica' }} devendo horas.
        </p>
      </div>
      <div v-if="auth.podeEscrever" class="flex flex-wrap gap-2.5">
        <button type="button" class="botao-primario min-h-11" @click="abrirPeriodo">
          <Icone nome="sol" tamanho="18" /> Marcar folga, férias ou atestado
        </button>
        <button v-if="podeFeriado" type="button" class="botao-secundario min-h-11" @click="abrirFeriado">
          <Icone nome="calendario" tamanho="18" /> Cadastrar feriado
        </button>
      </div>
    </header>

    <p v-if="!auth.podeEscrever" class="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[0.95rem] text-texto-2" role="status">
      <!-- em tela larga a barra de cima já mostra esta etiqueta -->
      <span class="selo selo-atencao lg:hidden">Somente consulta</span> Aqui você vê as folgas e os feriados, sem alterar nada.
    </p>

    <!-- Folgas, férias, atestados... da pessoa em tela -->
    <section class="cartao p-5 sm:p-6" aria-labelledby="titulo-periodos">
      <h2 id="titulo-periodos" class="titulo-secao">Folgas, férias e atestados</h2>
      <p class="text-[0.95rem] text-texto-3">
        O que está marcado de {{ anoAtual - 2 }} a {{ anoAtual + 1 }}{{ meus ? '' : ` para ${auth.pessoaEmTela?.nome ?? 'a pessoa'}` }}, do mais recente para o mais antigo.
      </p>

      <div class="mt-3 border-t border-borda" :class="{ 'pt-3': erroLista }">
        <EstadoDaTela
          :carregando="carregando"
          :erro="erroLista"
          :manter="listaCarregada"
          carregando-texto="Carregando as folgas e férias…"
          @tentar="carregar()"
        >
          <ul v-if="lista.length" class="divide-y divide-borda">
            <li
              v-for="a in lista"
              :key="a.id"
              class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 py-3.5 xl:grid-cols-[19rem_8rem_minmax(0,1fr)_auto]"
            >
              <p class="text-base">
                <b>{{ dataBR(a.dataInicio) }}</b> <span class="text-texto-3">({{ semana(a.dataInicio) }})</span>
                <template v-if="a.dataFim !== a.dataInicio">
                  a <b>{{ dataBR(a.dataFim) }}</b> <span class="text-texto-3">({{ semana(a.dataFim) }})</span>
                </template>
              </p>
              <p class="justify-self-end xl:justify-self-start"><span class="selo selo-info">{{ a.tipoRotulo }}</span></p>
              <p class="min-w-0 text-[0.95rem] break-words" :class="{ 'col-span-2 xl:col-span-1': !auth.podeEscrever }">
                <template v-if="a.descricao">{{ a.descricao }}</template>
                <span class="text-sm text-texto-3"><template v-if="a.descricao"> · </template>{{ quantosDias(a.dias) }} · marcado por {{ a.criadoPor }}</span>
              </p>
              <button
                v-if="auth.podeEscrever"
                type="button"
                class="botao-linha justify-self-end"
                :aria-label="`Remover ${a.tipoRotulo.toLowerCase()} de ${periodo(a)}`"
                @click="pedirRemocao('periodo', a, $event)"
              ><Icone nome="lixeira" tamanho="18" /> Remover</button>
            </li>
          </ul>
          <p v-else class="py-8 text-center text-[0.95rem] text-texto-3">
            Nenhuma folga, férias ou atestado marcado neste período.
          </p>
        </EstadoDaTela>
      </div>
    </section>

    <!-- Feriados (valem para todos) -->
    <section class="cartao p-5 sm:p-6" aria-labelledby="titulo-feriados">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="min-w-0">
          <h2 id="titulo-feriados" class="titulo-secao">Feriados de {{ anoFeriados }}</h2>
          <p class="text-[0.95rem] text-texto-3">
            Valem para todas as pessoas.<template v-if="auth.podeEscrever && !auth.ehAdmin"> Quem cadastra e remove é o administrador.</template>
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-3">
          <button v-if="anoFeriados !== anoAtual" type="button" class="link text-[0.95rem]" @click="mostrarFeriadosDe(anoAtual)">Voltar para {{ anoAtual }}</button>
          <div class="flex items-center gap-1 rounded-[14px] border border-borda bg-superficie p-1">
            <button type="button" class="grid size-11 place-items-center rounded-[10px] hover:bg-neutro" aria-label="Ano anterior" @click="mudarAnoFeriados(-1)">
              <Icone nome="esquerda" />
            </button>
            <span class="min-w-16 text-center text-lg font-extrabold" aria-live="polite">{{ anoFeriados }}</span>
            <button type="button" class="grid size-11 place-items-center rounded-[10px] hover:bg-neutro" aria-label="Próximo ano" @click="mudarAnoFeriados(1)">
              <Icone nome="direita" />
            </button>
          </div>
        </div>
      </div>

      <div class="mt-3 border-t border-borda" :class="{ 'pt-3': erroFeriados }">
        <EstadoDaTela
          :carregando="carregandoFeriados"
          :erro="erroFeriados"
          :manter="feriadosCarregados"
          carregando-texto="Carregando os feriados…"
          @tentar="carregarFeriados()"
        >
          <ul v-if="feriados.length" class="divide-y divide-borda">
            <li
              v-for="f in feriados"
              :key="f.data"
              class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 py-3.5 xl:grid-cols-[9rem_minmax(0,1fr)_auto_auto]"
            >
              <p class="text-base"><b>{{ diaMes(f.data) }}</b> <span class="text-texto-3">({{ semana(f.data) }})</span></p>
              <p class="row-start-2 min-w-0 text-base break-words xl:row-start-auto" :class="{ 'col-span-2 xl:col-span-1': !podeFeriado }">{{ f.descricao }}</p>
              <p class="justify-self-end"><span class="selo selo-neutro">{{ f.abrangenciaRotulo }}</span></p>
              <button
                v-if="podeFeriado"
                type="button"
                class="botao-linha row-start-2 justify-self-end xl:row-start-auto"
                :aria-label="`Remover o feriado ${f.descricao} de ${dataBR(f.data)}`"
                @click="pedirRemocao('feriado', f, $event)"
              ><Icone nome="lixeira" tamanho="18" /> Remover</button>
            </li>
          </ul>
          <p v-else class="py-8 text-center text-[0.95rem] text-texto-3">Nenhum feriado cadastrado em {{ anoFeriados }}.</p>
        </EstadoDaTela>
      </div>
    </section>

    <section class="cartao px-5 py-4 sm:px-6" aria-labelledby="titulo-como-folgas">
      <h2 id="titulo-como-folgas" class="text-base font-extrabold">Como estes dias entram na conta</h2>
      <p class="mt-1.5 text-[0.95rem] leading-relaxed text-texto-2">
        Num dia de folga, férias, atestado, licença ou feriado nada é previsto: o dia não fica devendo horas nem aparece
        como dia para corrigir. Se houver trabalho num desses dias, o tempo conta a favor. Para usar horas do banco numa
        folga (folga compensada), lance em <RouterLink :to="{ name: 'banco' }" class="link">Banco de horas</RouterLink>.
        As folgas e férias que já estão no relatório do RH podem ser trazidas em
        <RouterLink :to="{ name: 'conciliacao' }" class="link">Conferir com o RH</RouterLink>, e também dá para marcar um dia
        direto em <RouterLink :to="{ name: 'meu-ponto' }" class="link">{{ meus ? 'Meu ponto' : 'Ponto dia a dia' }}</RouterLink>.
      </p>
    </section>

    <Teleport to="body">
      <!-- Marcar folga, férias, atestado, licença ou outra justificativa -->
      <div v-if="janela === 'periodo'" class="janela-fundo" @mousedown.self="fecharJanela">
        <form role="dialog" aria-modal="true" aria-labelledby="titulo-janela-periodo" class="janela" novalidate @submit.prevent="cadastrar">
          <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
            <div>
              <h2 id="titulo-janela-periodo" class="text-xl font-extrabold tracking-tight">Marcar folga, férias ou atestado</h2>
              <p class="mt-1 text-[0.95rem] text-texto-3">Os dias marcados não ficam devendo horas.</p>
            </div>
            <button type="button" class="-mt-1 -mr-2 grid size-11 shrink-0 place-items-center rounded-xl text-texto-3 hover:bg-neutro hover:text-texto" aria-label="Fechar" @click="fecharJanela">
              <Icone nome="fechar" />
            </button>
          </header>

          <div class="flex flex-col gap-4 px-5 py-5">
            <fieldset>
              <legend class="rotulo">O que é</legend>
              <div class="mt-1.5 grid grid-cols-2 gap-2">
                <label
                  v-for="t in TIPOS"
                  :key="t.valor"
                  class="flex min-h-11 last:col-span-2 cursor-pointer items-center justify-center rounded-xl border px-3 py-2 text-center text-[0.95rem] font-semibold transition-colors has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-primaria"
                  :class="form.tipo === t.valor ? 'border-primaria bg-primaria-suave font-bold text-primaria' : 'border-borda-forte bg-superficie text-texto hover:bg-neutro'"
                >
                  <input v-model="form.tipo" type="radio" name="tipo-periodo" :value="t.valor" class="sr-only" />
                  {{ t.rotulo }}
                </label>
              </div>
            </fieldset>

            <div class="grid gap-4 sm:grid-cols-2">
              <div>
                <label for="periodo-inicio" class="rotulo">Primeiro dia</label>
                <input id="periodo-inicio" ref="primeiroCampo" v-model="form.dataInicio" type="date" class="campo mt-1.5" @change="aoMudarInicio" />
              </div>
              <div>
                <label for="periodo-fim" class="rotulo">Último dia</label>
                <input id="periodo-fim" v-model="form.dataFim" type="date" class="campo mt-1.5" :min="form.dataInicio || undefined" />
              </div>
            </div>
            <p class="-mt-2 text-sm text-texto-3">Para um dia só, deixe as duas datas iguais.</p>

            <div>
              <label for="periodo-descricao" class="rotulo">{{ justificativaObrigatoria ? 'Justificativa' : 'Descrição (opcional)' }}</label>
              <input
                id="periodo-descricao"
                v-model="form.descricao"
                type="text"
                maxlength="200"
                class="campo mt-1.5"
                :placeholder="tipoEscolhido?.exemplo"
              />
            </div>

            <p v-if="tentou && erroForm" class="aviso-erro" role="alert">{{ erroForm }}</p>
            <p v-if="erroCadastro" class="aviso-erro" role="alert">{{ erroCadastro }}</p>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-borda px-5 py-4 sm:flex-row sm:justify-end">
            <button type="button" class="botao-secundario min-h-11" :disabled="salvando" @click="fecharJanela">Cancelar</button>
            <button type="submit" class="botao-primario min-h-11" :disabled="salvando">
              {{ salvando ? 'Salvando…' : diasCorridos > 1 ? `Marcar ${diasCorridos} dias` : 'Marcar o dia' }}
            </button>
          </footer>
        </form>
      </div>

      <!-- Cadastrar feriado (administrador) -->
      <div v-if="janela === 'feriado'" class="janela-fundo" @mousedown.self="fecharJanela">
        <form role="dialog" aria-modal="true" aria-labelledby="titulo-janela-feriado" class="janela" novalidate @submit.prevent="cadastrarFeriado">
          <header class="flex items-start justify-between gap-4 border-b border-borda px-5 pt-5 pb-4">
            <div>
              <h2 id="titulo-janela-feriado" class="text-xl font-extrabold tracking-tight">Cadastrar feriado</h2>
              <p class="mt-1 text-[0.95rem] text-texto-3">O feriado vale para todas as pessoas: nesse dia ninguém fica devendo horas.</p>
            </div>
            <button type="button" class="-mt-1 -mr-2 grid size-11 shrink-0 place-items-center rounded-xl text-texto-3 hover:bg-neutro hover:text-texto" aria-label="Fechar" @click="fecharJanela">
              <Icone nome="fechar" />
            </button>
          </header>

          <div class="flex flex-col gap-4 px-5 py-5">
            <div class="grid gap-4 sm:grid-cols-2">
              <div>
                <label for="feriado-data" class="rotulo">Data</label>
                <input id="feriado-data" ref="primeiroCampo" v-model="formFeriado.data" type="date" class="campo mt-1.5" />
              </div>
              <div>
                <label for="feriado-abrangencia" class="rotulo">Onde vale</label>
                <select id="feriado-abrangencia" v-model="formFeriado.abrangencia" class="campo mt-1.5">
                  <option v-for="a in ABRANGENCIAS" :key="a.valor" :value="a.valor">{{ a.rotulo }}</option>
                </select>
              </div>
            </div>
            <div>
              <label for="feriado-nome" class="rotulo">Nome do feriado</label>
              <input id="feriado-nome" v-model="formFeriado.descricao" type="text" maxlength="120" class="campo mt-1.5" placeholder="Ex.: Corpus Christi" />
            </div>

            <p v-if="tentouFeriado && erroFeriado" class="aviso-erro" role="alert">{{ erroFeriado }}</p>
            <p v-if="erroCadastroFeriado" class="aviso-erro" role="alert">{{ erroCadastroFeriado }}</p>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-borda px-5 py-4 sm:flex-row sm:justify-end">
            <button type="button" class="botao-secundario min-h-11" :disabled="salvandoFeriado" @click="fecharJanela">Cancelar</button>
            <button type="submit" class="botao-primario min-h-11" :disabled="salvandoFeriado">
              {{ salvandoFeriado ? 'Salvando…' : 'Cadastrar feriado' }}
            </button>
          </footer>
        </form>
      </div>

      <!-- Confirmação antes de remover -->
      <div v-if="remocao" class="janela-fundo z-[55]" @mousedown.self="cancelarRemocao">
        <section role="alertdialog" aria-modal="true" aria-labelledby="titulo-remocao" aria-describedby="texto-remocao" class="janela max-w-md px-5 py-5">
          <h2 id="titulo-remocao" class="text-xl font-extrabold tracking-tight">{{ remocao.titulo }}</h2>
          <p id="texto-remocao" class="mt-2 text-[0.95rem] text-texto-2">{{ remocao.texto }}</p>
          <div class="mt-5 flex flex-wrap justify-end gap-2">
            <button ref="botaoCancelar" type="button" class="botao-secundario min-h-11" :disabled="removendo" @click="cancelarRemocao">Cancelar</button>
            <button type="button" class="botao-perigo min-h-11" :disabled="removendo" @click="confirmarRemocao">
              {{ removendo ? 'Removendo…' : 'Remover' }}
            </button>
          </div>
        </section>
      </div>
    </Teleport>
  </main>
</template>
