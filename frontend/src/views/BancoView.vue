<script setup>
import { computed, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { useCarga } from '@/composables/useCarga'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { corDoSaldo, dataBR, momento, saldo } from '@/utils/horas'
import AcoesDoPonto from '@/components/AcoesDoPonto.vue'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import GraficoSaldoAnual from '@/components/GraficoSaldoAnual.vue'
import Icone from '@/components/Icone.vue'

/**
 * Banco de horas: o saldo que o RH zera a cada fechamento, mês a mês, com as horas usadas ou somadas à mão e
 * os fechamentos anteriores. Vem pronto de GET /banco (saldo, frases, prazo, o que dá para fazer).
 */
const auth = useAuthStore()
const ponto = usePontoStore()

const acoes = ref(null)
const { tela, carregando, erro, carregar } = useCarga(() => pontoApi.banco())
carregar()
// um lançamento, um fechamento ou um dia corrigido (aqui ou em outra aba) muda o saldo: a tela é relida
watch(() => ponto.mudouEm, () => carregar({ silencioso: true }))
watch(() => ponto.reconectouEm, (momentoDaVolta) => momentoDaVolta && carregar({ silencioso: true }))

const meus = computed(() => auth.vendoOsProprios && auth.ehTitular)
const mesAtual = computed(() => tela.value?.meses.find((m) => m.atual) ?? null)
const mesesComMovimento = computed(() => (tela.value?.meses ?? []).filter((m) => m.saldoSegundos !== 0 || m.diasEmAberto > 0 || m.atual))
</script>

<template>
  <main class="pagina">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 class="titulo-pagina">Banco de horas<template v-if="!meus && auth.pessoaEmTela"> de {{ auth.pessoaEmTela.nome }}</template></h1>
        <p class="subtitulo-pagina">O que {{ meus ? 'você tem' : 'há' }} a favor ou devendo desde o último fechamento do RH.</p>
      </div>
      <div v-if="tela?.podeEditar" class="flex flex-wrap gap-2.5">
        <button type="button" class="botao-secundario min-h-11" @click="acoes.usarBanco()"><Icone nome="somar" tamanho="18" /> Usar ou somar horas</button>
        <button type="button" class="min-h-11" :class="tela.resumo?.urgente ? 'botao-primario' : 'botao-secundario'" @click="acoes.fecharBanco()">
          <Icone nome="cadeado" tamanho="18" /> Fechar o banco de horas
        </button>
      </div>
    </header>

    <EstadoDaTela :carregando="carregando" :erro="erro" :manter="!!tela" carregando-texto="Carregando o banco de horas…" @tentar="carregar()" />

    <template v-if="tela">
      <p v-if="tela.resumo?.urgente && tela.resumo.prazo" class="aviso-atencao flex flex-wrap items-center justify-between gap-3" role="status">
        <span class="flex items-center gap-2 text-[0.95rem]"><Icone nome="alerta" /> <b>{{ tela.resumo.prazo }}.</b> Quando o RH zerar o banco, registre o fechamento aqui.</span>
      </p>

      <section v-if="tela.resumo" class="flex flex-wrap gap-4" aria-label="Saldo do banco de horas">
        <div class="cartao flex flex-[1_1_17rem] flex-col gap-1.5 p-5 sm:p-6">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">Saldo de hoje</h2>
          <p class="text-[2.6rem] leading-none font-extrabold tracking-tight" :class="corDoSaldo(tela.resumo.saldoSegundos)">{{ saldo(tela.resumo.saldoSegundos) }}</p>
          <p class="text-base text-texto-2">{{ tela.resumo.texto }}</p>
          <p v-if="tela.ciclo.diasEmAberto" class="mt-1 text-sm text-atencao">
            {{ tela.ciclo.diasEmAberto }} dia{{ tela.ciclo.diasEmAberto === 1 ? '' : 's' }} com batida faltando
            {{ tela.ciclo.diasEmAberto === 1 ? 'fica' : 'ficam' }} fora deste saldo até a correção.
            <RouterLink :to="{ name: 'meu-ponto', query: { filtro: 'CORRIGIR' } }" class="link">Ver os dias</RouterLink>
          </p>
        </div>
        <div class="cartao flex flex-[1_1_17rem] flex-col gap-1.5 p-5 sm:p-6">
          <h2 class="text-[0.95rem] font-semibold text-texto-3">Período atual</h2>
          <p class="text-xl font-extrabold">{{ dataBR(tela.ciclo.dataInicio) }} a {{ dataBR(tela.ciclo.dataFimPrevista) }}</p>
          <p class="text-base" :class="tela.resumo.urgente ? 'font-semibold text-atencao' : 'text-texto-2'">{{ tela.resumo.prazo ?? 'Sem data prevista para o fechamento.' }}</p>
          <button v-if="tela.podeEditar" type="button" class="link mt-auto self-start pt-2 text-[0.95rem]" @click="acoes.corrigirPeriodo()">Corrigir as datas do período</button>
        </div>
      </section>
      <p v-else class="cartao p-6 text-center text-[0.95rem] text-texto-3">Ainda não há um período de banco de horas aberto.</p>

      <!-- Mês a mês -->
      <section v-if="tela.meses.length" class="cartao p-5 sm:p-6" aria-labelledby="titulo-meses">
        <h2 id="titulo-meses" class="titulo-secao">Mês a mês</h2>
        <GraficoSaldoAnual class="mt-4" :meses="tela.ciclo.meses" :destaque="mesAtual ? { ano: mesAtual.ano, mes: mesAtual.mes } : null" rotulo-acumulado="no período" />
        <ul class="mt-4 divide-y divide-borda border-t border-borda">
          <li
            v-for="m in mesesComMovimento"
            :key="`${m.ano}-${m.mes}`"
            class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 py-3 md:grid-cols-[11rem_minmax(0,1fr)_auto_auto]"
          >
            <span class="font-bold">{{ m.rotulo }} <span v-if="m.atual" class="selo selo-info ml-1 py-0.5!">mês atual</span></span>
            <span class="text-right font-bold md:text-left" :class="corDoSaldo(m.saldoSegundos)">{{ m.texto }}</span>
            <span class="text-sm text-texto-3">
              somando desde o início: <b class="text-texto">{{ saldo(m.acumuladoSegundos) }}</b>
              <span v-if="m.diasEmAberto" class="text-atencao"> · {{ m.diasEmAberto }} dia{{ m.diasEmAberto === 1 ? '' : 's' }} para corrigir</span>
            </span>
            <RouterLink :to="{ name: 'meu-ponto', query: { ano: m.ano, mes: m.mes } }" class="link text-right text-sm">Ver os dias</RouterLink>
          </li>
        </ul>
      </section>

      <!-- Horas usadas ou somadas à mão -->
      <section class="cartao p-5 sm:p-6" aria-labelledby="titulo-lancamentos">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 id="titulo-lancamentos" class="titulo-secao">Horas usadas ou somadas à mão</h2>
            <p class="text-[0.95rem] text-texto-3">Folga compensada, saída mais cedo combinada, horas pagas… Não mudam as batidas: entram direto no saldo.</p>
          </div>
          <button v-if="tela.podeEditar" type="button" class="botao-secundario min-h-11" @click="acoes.usarBanco()"><Icone nome="somar" tamanho="18" /> Novo lançamento</button>
        </div>
        <ul v-if="tela.lancamentos.length" class="mt-3 divide-y divide-borda border-t border-borda">
          <li
            v-for="l in tela.lancamentos"
            :key="l.id"
            class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1.5 py-3 md:grid-cols-[7rem_8rem_minmax(0,1fr)_auto]"
          >
            <span class="font-bold">{{ l.dia }}</span>
            <span class="text-right font-bold md:text-left" :class="corDoSaldo(l.segundos)">{{ saldo(l.segundos) }}</span>
            <span class="col-span-2 min-w-0 md:col-span-1">
              {{ l.descricao }}
              <span class="block text-sm text-texto-3">por {{ l.criadoPor }} em {{ momento(l.criadoEm) }}</span>
            </span>
            <button
              v-if="tela.podeEditar"
              type="button"
              class="botao-linha col-span-2 justify-self-start md:col-span-1"
              :disabled="ponto.salvando"
              @click="acoes.removerLancamento(l)"
            >Remover</button>
          </li>
        </ul>
        <p v-else class="mt-3 border-t border-borda pt-5 pb-2 text-center text-[0.95rem] text-texto-3">Nenhum lançamento neste período.</p>
      </section>

      <!-- Fechamentos anteriores -->
      <section v-if="tela.anteriores.length" class="cartao p-5 sm:p-6" aria-labelledby="titulo-anteriores">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 id="titulo-anteriores" class="titulo-secao">Fechamentos anteriores</h2>
          <button v-if="tela.podeDesfazer" type="button" class="link text-[0.95rem]" @click="acoes.desfazerFechamento()">Fechou por engano? Desfazer o último</button>
        </div>
        <ul class="mt-3 divide-y divide-borda border-t border-borda">
          <li v-for="a in tela.anteriores" :key="a.id" class="flex flex-wrap items-center gap-x-4 gap-y-1 py-3">
            <span class="font-bold">{{ a.periodo }}</span>
            <span class="min-w-0 flex-1 font-semibold" :class="corDoSaldo(a.saldoSegundos)">{{ a.texto }}</span>
            <span class="text-sm text-texto-3">registrado por {{ a.fechadoPor }} em {{ momento(a.fechadoEm) }}</span>
            <span v-if="a.observacao" class="w-full text-sm text-texto-2">Observação: {{ a.observacao }}</span>
          </li>
        </ul>
      </section>

      <section class="cartao px-5 py-4 sm:px-6" aria-labelledby="titulo-como-funciona">
        <h2 id="titulo-como-funciona" class="text-base font-extrabold">Como o banco de horas funciona</h2>
        <p class="mt-1.5 text-[0.95rem] leading-relaxed text-texto-2">{{ tela.explicacao }}</p>
      </section>
    </template>

    <AcoesDoPonto ref="acoes" />
  </main>
</template>
