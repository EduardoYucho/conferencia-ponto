<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { useCarga } from '@/composables/useCarga'
import { useRelogio } from '@/composables/useRelogio'
import { avisar } from '@/utils/avisar'
import { salvarResposta } from '@/utils/download'
import { mensagemDe } from '@/utils/erros'
import { corDoSaldo, duracao, saldo } from '@/utils/horas'
import { dataISO } from '@/utils/tempo'
import AcoesDoPonto from '@/components/AcoesDoPonto.vue'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'
import LinhaDoDia from '@/components/LinhaDoDia.vue'

/**
 * Início: como está o dia de hoje, o botão de bater o ponto, o que espera uma ação e os saldos.
 * Tudo vem pronto de GET /inicio (situação, frases, quanto falta, próxima batida): a tela não faz conta.
 * Enquanto está aberta, pergunta de novo a cada 30 s (o "trabalhado até agora" anda) e sempre que algo muda.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const router = useRouter()

const acoes = ref(null)
/** Diferença entre o relógio do servidor e o deste computador (o ponto é batido com o do servidor). */
const ajusteRelogio = ref(0)
let timer = null

const { tela, carregando, erro, carregar } = useCarga(() => pontoApi.inicio(), (dados) => {
  ajusteRelogio.value = new Date(`${dados.hoje}T${dados.agora}`).getTime() - Date.now()
  ponto.registrarDias([dados.dia.dia, ...dados.ultimosDias])
})

onMounted(() => {
  carregar()
  timer = setInterval(() => document.visibilityState === 'visible' && carregar({ silencioso: true }), 30_000)
  document.addEventListener('visibilitychange', aoVoltarParaAba)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', aoVoltarParaAba)
})
function aoVoltarParaAba() {
  if (document.visibilityState === 'visible') carregar({ silencioso: true })
}
// uma gravação daqui ou um evento do servidor (comprovante importado, folga marcada em outra aba...)
watch(() => ponto.mudouEm, () => carregar({ silencioso: true }))

const hoje = computed(() => tela.value?.dia ?? null)
const meus = computed(() => auth.vendoOsProprios && auth.ehTitular)
const primeiroNome = (nome) => (nome ?? '').trim().split(/\s+/)[0]
const titulo = computed(() => (meus.value
  ? `${tela.value?.saudacao ?? 'Olá'}, ${primeiroNome(auth.usuario?.nome)}`
  : `O ponto de ${primeiroNome(auth.pessoaEmTela?.nome)} hoje`))

/** Hora do servidor, andando (é com ela que a batida é registrada). */
const agora = useRelogio(1000)
const relogio = computed(() => new Date(agora.value.getTime() + ajusteRelogio.value)
  .toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', hour12: false }))

/** Bolinha do título de hoje. */
const corDeHoje = computed(() => ({
  TRABALHANDO: 'bg-positivo-solido',
  INTERVALO: 'bg-atencao-solido',
}[hoje.value?.situacao] ?? 'bg-texto-4'))

// ------------------------------------------------------------------ conexão e pasta dos comprovantes
const pastaCurta = computed(() => {
  const diretorio = ponto.monitoramento?.diretorio ?? ''
  const partes = diretorio.split(/[\\/]/).filter(Boolean)
  return partes.length ? partes.slice(-2).join('/') : ''
})
const estadoDaPasta = computed(() => {
  if (ponto.tempoReal !== 'conectado') {
    return { tom: 'atencao', pisca: true, texto: ponto.tempoReal === 'conectando' ? 'Conectando…' : 'Reconectando…' }
  }
  const monitor = ponto.monitoramento
  if (monitor?.ativo) return { tom: 'positivo', texto: meus.value ? 'Comprovantes chegando da sua pasta' : 'Comprovantes chegando da pasta' }
  if (monitor?.situacao === 'INDISPONIVEL') return { tom: 'atencao', pisca: true, texto: 'A pasta dos comprovantes não está acessível' }
  if (monitor?.situacao === 'INICIANDO') return { tom: 'atencao', pisca: true, texto: 'Conectando à pasta dos comprovantes…' }
  return { tom: 'neutro', texto: 'A tela se atualiza sozinha' }
})
const dicaDaPasta = computed(() => {
  const monitor = ponto.monitoramento
  if (!monitor?.diretorio) return 'Quando chega uma batida nova, a tela se atualiza sozinha. Sem pasta de comprovantes: envie os PDFs pela tela.'
  return `Pasta dos comprovantes: ${monitor.diretorio}${monitor.mensagem ? `\n${monitor.mensagem}` : ''}`
})

// ------------------------------------------------------------------ ações
const batendo = ref(false)
async function bater() {
  batendo.value = true
  try {
    await acoes.value.bater()
  } finally {
    batendo.value = false
  }
}

/** Dia da lista: "abrir" leva ao Meu ponto com o dia aberto; o resto é ação do próprio dia. */
function aoAgirNoDia(nome, dia) {
  if (nome === 'abrir') return abrirNoMes(dia.data)
  return acoes.value.executar(nome, dia)
}
function abrirNoMes(data, filtro = undefined) {
  const [ano, mes] = (data ?? tela.value.hoje).split('-').map(Number)
  router.push({ name: 'meu-ponto', query: { ano, mes, ...(data ? { dia: data } : {}), ...(filtro ? { filtro } : {}) } })
}

/** O botão de cada pendência (o servidor diz qual é a pendência e o texto do botão). */
function resolver(p) {
  if (p.tipo === 'DIVERGENCIAS_RH') return router.push({ name: 'conciliacao' })
  if (p.tipo === 'BANCO_PRAZO') return router.push({ name: 'banco' })
  const dia = p.data ? tela.value.ultimosDias.find((d) => d.data === p.data) : null
  if (dia && p.tipo === 'DIA_INCOMPLETO' && dia.acoes.corrigir) return acoes.value.executar('corrigir', dia)
  if (p.data) return abrirNoMes(p.data)
  return abrirNoMes(null, 'CORRIGIR')
}

/** "Esqueci de bater ou bati errado": abre os horários de hoje para corrigir. */
const podeCorrigirHoje = computed(() => {
  const a = hoje.value?.dia.acoes
  return !!a && (a.ajustar || a.informarBatidas)
})

const exportando = ref(false)
async function baixarPlanilha() {
  if (exportando.value) return
  exportando.value = true
  try {
    salvarResposta(await pontoApi.exportarPlanilha(), `conferencia-ponto-${auth.pessoaEmTela?.login ?? 'eu'}-${dataISO()}.xlsx`)
  } catch (e) {
    avisar(`Não foi possível baixar a planilha. ${mensagemDe(e)}`, 'erro')
  } finally {
    exportando.value = false
  }
}
</script>

<template>
  <main class="pagina">
    <header class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="titulo-pagina">{{ titulo }}</h1>
        <p class="subtitulo-pagina">{{ tela?.dataPorExtenso ?? ' ' }}</p>
      </div>
      <p
        class="selo px-3! py-2! font-semibold!"
        :class="`selo-${estadoDaPasta.tom}`"
        :title="dicaDaPasta"
        role="status"
      >
        <span class="size-2 rounded-full bg-current" :class="{ 'animate-pulse': estadoDaPasta.pisca }" aria-hidden="true" />
        {{ estadoDaPasta.texto }}<template v-if="estadoDaPasta.tom === 'positivo' && pastaCurta"> <span class="hidden font-normal xl:inline">(…/{{ pastaCurta }})</span></template>
      </p>
    </header>

    <EstadoDaTela :carregando="carregando" :erro="erro" :manter="!!tela" carregando-texto="Carregando o dia de hoje…" @tentar="carregar()" />

    <template v-if="tela">
      <!-- Hoje -->
      <section class="cartao flex flex-wrap gap-x-7 gap-y-5 p-5 sm:p-6" aria-labelledby="titulo-hoje">
        <div class="flex min-w-0 flex-[3_1_26rem] flex-col gap-4">
          <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
            <span class="size-2.5 shrink-0 rounded-full" :class="[corDeHoje, { 'animate-pulse': hoje.trabalhando }]" aria-hidden="true" />
            <h2 id="titulo-hoje" class="text-xl font-extrabold">{{ hoje.titulo }}</h2>
            <span v-if="hoje.detalhe" class="text-[0.95rem] text-texto-3">{{ hoje.detalhe }}</span>
          </div>

          <template v-if="hoje.previstoSegundos > 0 || hoje.trabalhadoSegundos > 0">
            <div class="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
              <span class="text-[2.9rem] leading-none font-extrabold tracking-tight">{{ duracao(hoje.trabalhadoSegundos) }}</span>
              <span class="text-[1.05rem] text-texto-3">
                {{ hoje.trabalhadoSegundos < 3600 ? 'trabalhados' : 'trabalhadas' }} hoje<template v-if="hoje.previstoSegundos">, de {{ duracao(hoje.previstoSegundos) }}</template>
              </span>
            </div>
            <div
              v-if="hoje.previstoSegundos"
              class="h-2.5 overflow-hidden rounded-full bg-neutro"
              role="img"
              :aria-label="`${hoje.percentual}% do dia concluído`"
            >
              <div class="h-full rounded-full transition-[width] duration-500" :class="hoje.percentual >= 100 ? 'bg-positivo-solido' : 'bg-botao'" :style="{ width: `${Math.min(hoje.percentual, 100)}%` }" />
            </div>
          </template>
          <p v-if="hoje.resumo" class="text-[0.95rem] text-texto-2">{{ hoje.resumo }}</p>

          <div v-if="hoje.dia.batidas.length || (hoje.proximaBatida && hoje.previstoSegundos)" class="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
            <div v-for="b in hoje.dia.batidas" :key="b.tipo" class="rounded-xl border border-borda px-3 py-2.5">
              <p class="truncate text-[0.8rem] text-texto-3">{{ b.rotulo }}</p>
              <p class="text-xl font-bold" :title="b.real">{{ b.hora }}</p>
              <p v-if="b.nota" class="text-xs font-semibold" :class="b.tom === 'NEGATIVO' ? 'text-negativo' : 'text-positivo'">{{ b.nota }}</p>
              <p v-else-if="b.ajustada" class="text-xs text-texto-3">informada à mão</p>
            </div>
            <div v-if="hoje.proximaBatida" class="rounded-xl border-2 border-dashed border-primaria bg-primaria-suave px-[11px] py-[9px]">
              <p class="truncate text-[0.8rem] font-semibold text-primaria">{{ hoje.proximaBatida }}</p>
              <p class="text-xl font-bold text-primaria">a bater</p>
              <p v-if="hoje.proximaPrevista" class="text-xs text-texto-2">prevista {{ hoje.proximaPrevista }}</p>
            </div>
          </div>
        </div>

        <div class="flex flex-[1_1_16rem] flex-col justify-center gap-3">
          <template v-if="hoje.podeBater">
            <button
              type="button"
              class="botao-primario min-h-16 rounded-[14px]! text-[1.15rem]! font-extrabold!"
              :disabled="batendo || ponto.salvando"
              @click="bater"
            >
              <Icone nome="relogio" tamanho="22" />
              {{ batendo ? 'Registrando…' : hoje.rotuloBotao }}
            </button>
            <p class="text-center text-sm text-texto-3">Registra com o horário do sistema: <b class="text-texto">{{ relogio }}</b></p>
          </template>
          <p v-else-if="hoje.aviso" class="aviso-atencao">{{ hoje.aviso }}</p>
          <p v-else-if="hoje.rotuloBotao && meus" class="selo selo-positivo justify-center py-3! text-base!"><Icone nome="certo" /> {{ hoje.rotuloBotao }}</p>
          <button v-if="podeCorrigirHoje" type="button" class="botao-secundario min-h-11" @click="acoes.executar('ajustar', hoje.dia)">
            Esqueci de bater ou bati errado
          </button>
          <button v-else-if="hoje.dia.acoes.editarLancamento" type="button" class="botao-secundario min-h-11" @click="acoes.executar('editarLancamento', hoje.dia)">
            Editar as horas de hoje
          </button>
        </div>
      </section>

      <!-- Saldos e pendências -->
      <section class="flex flex-wrap gap-4" aria-label="Resumo">
        <div class="cartao flex flex-[1_1_15.5rem] flex-col gap-1.5 p-5">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">Saldo de {{ tela.mes.nome }}</h2>
          <p class="text-[1.9rem] leading-tight font-extrabold tracking-tight" :class="corDoSaldo(tela.mes.saldoSegundos)">{{ saldo(tela.mes.saldoSegundos) }}</p>
          <p class="text-[0.95rem] text-texto-2">{{ tela.mes.texto }}</p>
          <RouterLink :to="{ name: 'meu-ponto' }" class="link mt-auto pt-2 text-[0.95rem]">Ver o mês dia a dia</RouterLink>
        </div>
        <div class="cartao flex flex-[1_1_15.5rem] flex-col gap-1.5 p-5">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">Banco de horas</h2>
          <template v-if="tela.banco">
            <p class="text-[1.9rem] leading-tight font-extrabold tracking-tight" :class="corDoSaldo(tela.banco.saldoSegundos)">{{ saldo(tela.banco.saldoSegundos) }}</p>
            <p class="text-[0.95rem] text-texto-2">{{ tela.banco.texto }}</p>
            <p v-if="tela.banco.prazo" class="text-sm" :class="tela.banco.urgente ? 'font-semibold text-atencao' : 'text-texto-3'">{{ tela.banco.prazo }}</p>
          </template>
          <p v-else class="text-[0.95rem] text-texto-3">Ainda não há um período de banco de horas aberto.</p>
          <RouterLink :to="{ name: 'banco' }" class="link mt-auto pt-2 text-[0.95rem]">Ver o banco de horas</RouterLink>
        </div>
        <div
          v-if="tela.pendencias.length"
          class="flex flex-[1.4_1_20rem] flex-col gap-3 rounded-2xl border border-atencao-borda bg-atencao-suave p-5"
        >
          <h2 class="text-[0.95rem] font-bold text-atencao">{{ meus ? 'Precisa da sua atenção' : 'Pede atenção' }} ({{ tela.pendencias.length }})</h2>
          <div v-for="p in tela.pendencias" :key="p.tipo" class="flex items-center justify-between gap-3">
            <span class="text-[0.95rem]">{{ p.texto }}</span>
            <button type="button" class="botao-escuro min-h-11 shrink-0" @click="resolver(p)">{{ p.acao }}</button>
          </div>
        </div>
        <div v-else class="flex flex-[1.4_1_20rem] items-center gap-3 rounded-2xl border border-positivo-borda bg-positivo-suave p-5 text-positivo">
          <Icone nome="certo" tamanho="26" />
          <div>
            <h2 class="text-base font-bold">Tudo em dia</h2>
            <p class="text-[0.95rem]">Nenhum dia para corrigir e nada para conferir com o RH.</p>
          </div>
        </div>
      </section>

      <!-- Últimos dias -->
      <section class="cartao px-5 pt-5 pb-2 sm:px-6" aria-labelledby="titulo-ultimos">
        <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
          <h2 id="titulo-ultimos" class="titulo-secao">Últimos dias</h2>
          <RouterLink :to="{ name: 'meu-ponto' }" class="link text-[0.95rem]">Ver o mês inteiro</RouterLink>
        </div>
        <ul v-if="tela.ultimosDias.length">
          <LinhaDoDia v-for="d in tela.ultimosDias" :key="d.data" :dia="d" compacto @acao="aoAgirNoDia" />
        </ul>
        <p v-else class="py-6 text-center text-[0.95rem] text-texto-3">Ainda não há dias com registro.</p>
      </section>

      <!-- O que dá para fazer -->
      <section class="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="O que você pode fazer">
        <template v-if="auth.podeEscrever">
          <button type="button" class="acao-rapida" @click="acoes.marcar()"><Icone nome="sol" class="text-primaria" /> Marcar folga, férias ou feriado</button>
          <button type="button" class="acao-rapida" @click="acoes.enviarComprovantes()"><Icone nome="enviar" class="text-primaria" /> Enviar comprovantes (PDF)</button>
          <button type="button" class="acao-rapida" @click="acoes.usarBanco()"><Icone nome="somar" class="text-primaria" /> Usar ou somar horas do banco</button>
        </template>
        <button type="button" class="acao-rapida" :disabled="exportando" @click="baixarPlanilha">
          <Icone nome="baixar" class="text-primaria" /> {{ exportando ? 'Gerando a planilha…' : 'Baixar planilha (Excel)' }}
        </button>
      </section>
    </template>

    <AcoesDoPonto ref="acoes" />
  </main>
</template>
