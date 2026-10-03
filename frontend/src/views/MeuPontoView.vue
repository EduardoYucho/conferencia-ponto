<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pontoApi } from '@/api/pontoApi'
import { useCarga } from '@/composables/useCarga'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { avisar } from '@/utils/avisar'
import { salvarResposta } from '@/utils/download'
import { mensagemDe } from '@/utils/erros'
import { duracao, saldo, sentido } from '@/utils/horas'
import { dataISO } from '@/utils/tempo'
import AcoesDoPonto from '@/components/AcoesDoPonto.vue'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'
import LinhaDoDia from '@/components/LinhaDoDia.vue'

/**
 * Meu ponto: o mês dia a dia, com as batidas, o saldo de cada dia, os comprovantes e o que dá para fazer.
 * Vem pronto de GET /ponto (situação, frase, cor, filtros e ações de cada dia; totais do mês): a tela só mostra.
 * Substitui a antiga "Auditoria" e o cartão mensal do painel.
 */
const auth = useAuthStore()
const ponto = usePontoStore()
const route = useRoute()
const router = useRouter()

const FILTROS = [
  { valor: null, rotulo: 'Todos os dias' },
  { valor: 'CORRIGIR', rotulo: 'Para corrigir' },
  { valor: 'DIFERENCA', rotulo: 'Com atraso ou hora a mais' },
  { valor: 'FOLGA', rotulo: 'Folgas e feriados' },
  { valor: 'AJUSTADO', rotulo: 'Ajustados à mão' },
]
/** Quantos dias aparecem antes do botão "Mostrar os outros". */
const PRIMEIROS = 12

const meus = computed(() => auth.vendoOsProprios && auth.ehTitular)
const acoes = ref(null)
const filtro = ref(FILTROS.some((f) => f.valor === route.query.filtro) ? route.query.filtro : null)
const mostrarTodos = ref(false)
const abertos = ref(new Set())
const proximosAbertos = ref(false)
const destaque = ref(null) // { data, chave }: realça o dia que acabou de mudar
/** Mês pedido no endereço (?ano=&mes=); sem isso (ou com algo que não é um mês), o servidor usa o mês de hoje. */
function mesDoEndereco() {
  const ano = Number(route.query.ano)
  const mes = Number(route.query.mes)
  const valido = Number.isInteger(ano) && ano >= 2000 && ano <= 2100 && Number.isInteger(mes) && mes >= 1 && mes <= 12
  return valido ? { ano, mes } : { ano: undefined, mes: undefined }
}
let referencia = mesDoEndereco()

const { tela, carregando, erro, carregar } = useCarga(
  () => pontoApi.meuPonto(referencia.ano, referencia.mes),
  (dados) => ponto.registrarDias([...dados.dias, ...dados.proximos]),
)

function irPara(mes) {
  referencia = mes
  mostrarTodos.value = false
  abertos.value = new Set()
  proximosAbertos.value = false
  router.replace({ query: { ...(mes.ano ? { ano: mes.ano, mes: mes.mes } : {}), ...(filtro.value ? { filtro: filtro.value } : {}) } })
  carregar()
}

carregar().then(async () => {
  // veio do Início para resolver um dia: ele já aparece aberto e à vista
  const dia = typeof route.query.dia === 'string' ? route.query.dia : null
  if (!dia || !tela.value?.dias.some((d) => d.data === dia)) return
  mostrarTodos.value = true
  abertos.value = new Set([dia])
  destaque.value = { data: dia, chave: Date.now() }
  await nextTick()
  document.getElementById(`dia-${dia}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
})

// uma gravação daqui ou um evento do servidor: o mês em tela é relido e o dia que mudou fica realçado
watch(() => ponto.mudouEm, async () => {
  const mudanca = ponto.ultimaMudanca
  await carregar({ silencioso: true })
  if (mudanca?.data && Date.now() - mudanca.em < 5000) destaque.value = { data: mudanca.data, chave: Date.now() }
})
// a conexão voltou depois de uma queda: o que mudou nesse meio-tempo não chegou por evento
watch(() => ponto.reconectouEm, (momento) => momento && carregar({ silencioso: true }))

function escolherFiltro(valor) {
  filtro.value = valor
  mostrarTodos.value = false
  router.replace({ query: { ...route.query, filtro: valor ?? undefined, dia: undefined } })
}

const diasFiltrados = computed(() => (tela.value?.dias ?? [])
  .filter((d) => !filtro.value || d.filtros.includes(filtro.value)))
const diasVisiveis = computed(() => (mostrarTodos.value || filtro.value ? diasFiltrados.value : diasFiltrados.value.slice(0, PRIMEIROS)))
const escondidos = computed(() => diasFiltrados.value.length - diasVisiveis.value.length)
const paraCorrigir = computed(() => (tela.value ? tela.value.totais.diasParaCorrigir + tela.value.totais.diasSemRegistro : 0))

function alternar(dia) {
  const novo = new Set(abertos.value)
  if (!novo.delete(dia.data)) novo.add(dia.data)
  abertos.value = novo
}

// ------------------------------------------------------------------ comprovantes
const baixando = ref(new Set())
async function baixar(comprovante) {
  if (baixando.value.has(comprovante.id)) return
  baixando.value = new Set(baixando.value).add(comprovante.id)
  try {
    salvarResposta(await pontoApi.baixarComprovante(comprovante.id), `comprovante_${comprovante.id}.pdf`)
  } catch (e) {
    avisar(`Não foi possível baixar o comprovante (${comprovante.rotulo}). ${mensagemDe(e)}`, 'erro')
  } finally {
    const restante = new Set(baixando.value)
    restante.delete(comprovante.id)
    baixando.value = restante
  }
}
async function baixarTodos(dia) {
  for (const comprovante of dia.comprovantes) {
    await baixar(comprovante)
    await new Promise((resolve) => setTimeout(resolve, 350)) // o navegador aceita downloads em sequência
  }
}

// ------------------------------------------------------------------ planilha de conferência
const exportando = ref(false)
ponto.carregarPlanilha()
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
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="titulo-pagina">{{ meus ? 'Meu ponto' : `Ponto de ${auth.pessoaEmTela?.nome ?? '…'}` }}</h1>
        <p class="subtitulo-pagina">Cada dia do mês, com as batidas e o saldo do dia.</p>
      </div>
      <div class="flex flex-col items-end gap-1.5">
        <div class="flex flex-wrap justify-end gap-2.5">
          <button v-if="auth.podeEscrever" type="button" class="botao-secundario min-h-11" @click="acoes.enviarComprovantes()">
            <Icone nome="enviar" tamanho="18" /> Enviar comprovantes
          </button>
          <button type="button" class="botao-secundario min-h-11" :disabled="exportando" title="Todos os meses, dia a dia, com os totais e o banco de horas" @click="baixarPlanilha">
            <Icone nome="baixar" tamanho="18" /> {{ exportando ? 'Gerando…' : 'Baixar planilha (Excel)' }}
          </button>
          <a
            v-if="ponto.planilha?.url"
            :href="ponto.planilha.url"
            target="_blank"
            rel="noopener"
            class="botao-secundario min-h-11"
            :title="ponto.planilha.situacao === 'ERRO' ? ponto.planilha.erro : 'Planilha do Google atualizada automaticamente'"
          >
            <span class="size-2 rounded-full" :class="ponto.planilha.situacao === 'SINCRONIZADA' ? 'bg-positivo-solido' : 'bg-atencao-solido'" aria-hidden="true" />
            Abrir no Google Planilhas <Icone nome="externo" tamanho="16" />
          </a>
        </div>
        <RouterLink
          v-if="ponto.planilha && !ponto.planilha.url && meus"
          :to="{ name: 'conta', hash: '#planilha' }"
          class="link text-sm"
        >Manter uma planilha no Google sempre atualizada</RouterLink>
      </div>
    </header>

    <!-- Mês -->
    <div class="flex flex-wrap items-center gap-3">
      <div class="flex items-center gap-1 rounded-[14px] border border-borda bg-superficie p-1">
        <button type="button" class="grid size-11 place-items-center rounded-[10px] hover:bg-neutro disabled:opacity-40" aria-label="Mês anterior" :disabled="!tela" @click="irPara(tela.anterior)">
          <Icone nome="esquerda" />
        </button>
        <span class="min-w-[11.5rem] text-center text-lg font-extrabold" aria-live="polite">{{ tela?.titulo ?? '…' }}</span>
        <button type="button" class="grid size-11 place-items-center rounded-[10px] hover:bg-neutro disabled:opacity-40" aria-label="Próximo mês" :disabled="!tela" @click="irPara(tela.proximo)">
          <Icone nome="direita" />
        </button>
      </div>
      <button v-if="tela && !tela.mesAtual" type="button" class="link text-[0.95rem]" @click="irPara({ ano: undefined, mes: undefined })">Voltar para o mês atual</button>
      <span v-if="carregando && tela" class="text-sm text-texto-3" role="status">Carregando…</span>
    </div>

    <EstadoDaTela :carregando="carregando" :erro="erro" :manter="!!tela" carregando-texto="Carregando o mês…" @tentar="carregar()" />

    <template v-if="tela">
      <!-- Totais -->
      <section class="grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4" aria-label="Resumo do mês">
        <div class="cartao px-5 py-4">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">{{ meus ? 'Você trabalhou' : 'Trabalhou' }}</h2>
          <p class="mt-1 text-[1.65rem] leading-tight font-extrabold tracking-tight">{{ duracao(tela.totais.trabalhadoSegundos) }}</p>
          <p class="text-sm text-texto-3">em {{ tela.totais.diasFechados }} dia{{ tela.totais.diasFechados === 1 ? '' : 's' }} fechado{{ tela.totais.diasFechados === 1 ? '' : 's' }}</p>
        </div>
        <div class="cartao px-5 py-4">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">O previsto era</h2>
          <p class="mt-1 text-[1.65rem] leading-tight font-extrabold tracking-tight">{{ duracao(tela.totais.previstoSegundos) }}</p>
          <p class="text-sm text-texto-3">nesses mesmos dias</p>
        </div>
        <div
          class="rounded-2xl border px-5 py-4"
          :class="tela.totais.saldoSegundos > 0 ? 'border-positivo-borda bg-positivo-suave text-positivo'
            : tela.totais.saldoSegundos < 0 ? 'border-negativo-borda bg-negativo-suave text-negativo' : 'border-borda bg-superficie'"
        >
          <h2 class="text-[0.95rem] font-semibold" :class="tela.totais.saldoSegundos ? '' : 'text-texto-3'">Saldo do mês</h2>
          <p class="mt-1 text-[1.65rem] leading-tight font-extrabold tracking-tight">{{ saldo(tela.totais.saldoSegundos) }}</p>
          <p class="text-sm">
            {{ tela.totais.saldoSegundos > 0 && meus ? 'a seu favor' : sentido(tela.totais.saldoSegundos) }}<template v-if="tela.totais.lancadoSegundos">
              · inclui {{ saldo(tela.totais.lancadoSegundos) }} do banco</template>
          </p>
        </div>
        <div v-if="paraCorrigir" class="rounded-2xl border border-atencao-borda bg-atencao-suave px-5 py-4 text-atencao">
          <h2 class="text-[0.95rem] font-semibold">Para corrigir</h2>
          <p class="mt-1 text-[1.65rem] leading-tight font-extrabold tracking-tight">{{ paraCorrigir }} dia{{ paraCorrigir === 1 ? '' : 's' }}</p>
          <p class="text-sm">{{ paraCorrigir === 1 ? 'fica' : 'ficam' }} fora do saldo até corrigir</p>
        </div>
        <div v-else class="cartao px-5 py-4">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">Para corrigir</h2>
          <p class="mt-1 flex items-center gap-2 text-[1.65rem] leading-tight font-extrabold tracking-tight text-positivo"><Icone nome="certo" tamanho="26" /> Nada</p>
          <p class="text-sm text-texto-3">nenhum dia com batida faltando</p>
        </div>
      </section>

      <!-- Filtros -->
      <div class="flex flex-wrap gap-2" role="group" aria-label="Mostrar">
        <button
          v-for="f in FILTROS"
          :key="f.rotulo"
          type="button"
          class="pilula"
          :class="{ 'pilula-ativa': filtro === f.valor }"
          :aria-pressed="filtro === f.valor"
          @click="escolherFiltro(f.valor)"
        >
          {{ f.rotulo }}<template v-if="f.valor"> ({{ tela.contagem[f.valor] ?? 0 }})</template>
        </button>
      </div>

      <!-- Dias -->
      <section class="cartao px-5 py-1 sm:px-6" aria-label="Dias do mês">
        <ul v-if="diasVisiveis.length">
          <LinhaDoDia
            v-for="d in diasVisiveis"
            :key="d.data"
            :dia="d"
            :aberto="abertos.has(d.data)"
            :realce="destaque?.data === d.data ? destaque.chave : null"
            :baixando="baixando"
            :salvando="ponto.salvando"
            @acao="(nome, dia) => acoes.executar(nome, dia)"
            @alternar="alternar"
            @baixar="baixar"
            @baixar-todos="baixarTodos"
            @remover-lancamento="(l) => acoes.removerLancamento(l)"
          />
        </ul>
        <p v-else class="py-10 text-center text-[0.95rem] text-texto-3">
          {{ filtro ? 'Nenhum dia deste mês entra neste filtro.' : 'Este mês ainda não começou: veja os próximos dias abaixo.' }}
        </p>
        <div v-if="escondidos > 0" class="flex flex-wrap items-center justify-between gap-3 border-t border-borda py-4">
          <span class="text-[0.95rem] text-texto-3">Mostrando {{ diasVisiveis.length }} dos {{ diasFiltrados.length }} dias</span>
          <button type="button" class="botao-linha" @click="mostrarTodos = true">Mostrar os outros {{ escondidos }} dias</button>
        </div>
      </section>

      <!-- Próximos dias (para planejar folga e férias) -->
      <section v-if="tela.proximos.length" class="cartao px-5 py-1 sm:px-6">
        <h2>
          <button
            type="button"
            class="flex min-h-14 w-full items-center justify-between gap-3 text-left"
            :aria-expanded="proximosAbertos"
            aria-controls="proximos-dias"
            @click="proximosAbertos = !proximosAbertos"
          >
            <span class="titulo-secao">Próximos dias <span class="text-[0.95rem] font-semibold text-texto-3">({{ tela.proximos.length }})</span></span>
            <span class="flex items-center gap-1 text-[0.95rem] font-semibold text-primaria">
              {{ proximosAbertos ? 'Esconder' : 'Mostrar' }} <Icone nome="abaixo" tamanho="18" class="transition-transform" :class="{ 'rotate-180': proximosAbertos }" />
            </span>
          </button>
        </h2>
        <ul v-if="proximosAbertos" id="proximos-dias" class="border-t border-borda">
          <LinhaDoDia
            v-for="d in tela.proximos"
            :key="d.data"
            :dia="d"
            :aberto="abertos.has(d.data)"
            :realce="destaque?.data === d.data ? destaque.chave : null"
            :salvando="ponto.salvando"
            @acao="(nome, dia) => acoes.executar(nome, dia)"
            @alternar="alternar"
            @remover-lancamento="(l) => acoes.removerLancamento(l)"
          />
        </ul>
      </section>

      <section class="cartao px-5 py-4 sm:px-6" aria-labelledby="titulo-explicacao">
        <h2 id="titulo-explicacao" class="text-base font-extrabold">Como o saldo do dia é calculado</h2>
        <p class="mt-1.5 text-[0.95rem] leading-relaxed text-texto-2">{{ tela.explicacao }}</p>
      </section>
    </template>

    <AcoesDoPonto ref="acoes" />
  </main>
</template>
