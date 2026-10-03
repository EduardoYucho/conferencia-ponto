<script setup>
import { computed } from 'vue'
import { duracao, momento, saldo, seloDoTom, textoDoTom } from '@/utils/horas'
import Icone from '@/components/Icone.vue'

/**
 * Um dia na lista (Início e Meu ponto): as batidas, quanto foi trabalhado, como o dia ficou e o que dá para
 * fazer com ele. Tudo vem decidido do servidor (`dia.situacaoTexto`, `dia.tom`, `dia.acoes`...): aqui só se mostra.
 *
 * `compacto` (Início): uma linha só, com no máximo um botão. Sem `compacto` (Meu ponto): o dia abre para mostrar
 * cada batida em detalhe, os comprovantes, o histórico de ajustes e as demais ações.
 */
const props = defineProps({
  /** DiaResponse da API. */
  dia: { type: Object, required: true },
  compacto: { type: Boolean, default: false },
  aberto: { type: Boolean, default: false },
  /** Realça a linha (acabou de mudar). */
  realce: { type: [Number, String], default: null },
  /** Ids dos comprovantes sendo baixados. */
  baixando: { type: Set, default: () => new Set() },
  salvando: { type: Boolean, default: false },
})
const emit = defineEmits(['acao', 'alternar', 'baixar', 'baixar-todos', 'remover-lancamento'])

const d = computed(() => props.dia)
/** A situação é um saldo ("+ 15 min a favor", "em dia"): vai como texto; as outras, como etiqueta. */
const situacaoEhSaldo = computed(() => ['EM_DIA', 'A_FAVOR', 'DEVENDO'].includes(d.value.situacao))
const semBatidas = computed(() => !d.value.batidas.length)
/** Dia sem batidas cuja situação merece etiqueta ("Sem registro"); folga e feriado já mostram a marcação. */
const mostraEtiqueta = computed(() => semBatidas.value && !d.value.marcador
  && !['SEM_EXPEDIENTE', 'FERIADO', 'AUSENCIA', 'FUTURO'].includes(d.value.situacao))
/** Colunas da linha em tela larga (larguras fixas: os dias ficam alinhados entre si). */
const colunas = computed(() => (props.compacto
  ? 'xl:grid-cols-[9.5rem_minmax(0,1fr)_7.5rem_11rem_9rem]'
  : 'xl:grid-cols-[7.5rem_minmax(0,1fr)_6rem_10rem_16.5rem]'))
const areas = computed(() => (semBatidas.value
  ? "xl:[grid-template-areas:'dia_bat_ac_ac_ac']"
  : "xl:[grid-template-areas:'dia_bat_trab_sit_ac']"))
const mostraTrabalhado = computed(() => d.value.trabalhadoSegundos !== null && d.value.situacao !== 'EM_ANDAMENTO')
const apagado = computed(() => semBatidas.value && ['SEM_EXPEDIENTE', 'FUTURO'].includes(d.value.situacao))
const rotuloMarcacao = computed(() => (d.value.marcador?.rotulo ?? 'marcação').toLowerCase())
const artigoMarcacao = computed(() => (/^férias/.test(rotuloMarcacao.value) ? 'as' : /^(folga|licença)/.test(rotuloMarcacao.value) ? 'a' : 'o'))

/** Cor da hora de uma batida fora do horário (o servidor diz se ela tirou ou somou tempo). */
const corDaBatida = (b) => (b.tom === 'NEGATIVO' ? 'font-bold text-negativo' : b.tom === 'POSITIVO' ? 'font-bold text-positivo' : '')

/** Há algo além da linha para mostrar ao abrir o dia. */
const temDetalhe = computed(() => !props.compacto && (
  d.value.batidas.length > 0 || d.value.lancamentos.length > 0 || d.value.ajustes.length > 0
  || Object.values(d.value.acoes ?? {}).some(Boolean)
))
const acao = (nome) => emit('acao', nome, d.value)
const horas = (lista) => (lista.length ? lista.map((h) => h.slice(0, 5)).join(' · ') : 'sem batidas')
</script>

<template>
  <li
    :id="`dia-${d.data}`"
    :key="realce ?? d.data"
    class="border-t border-borda first:border-t-0"
    :class="{ realce: realce }"
  >
    <!--
      Celular e telas estreitas: o dia e a situação em cima, as batidas e os botões embaixo.
      Tela larga: uma linha só, com as colunas alinhadas entre os dias (larguras fixas). Num dia sem batidas a
      etiqueta vai junto do texto e os botões ocupam o espaço das colunas de horas e de saldo.
    -->
    <div
      class="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 py-3.5 [grid-template-areas:'dia_sit''bat_bat''ac_ac']"
      :class="[colunas, areas]"
    >
      <!-- o dia -->
      <p class="[grid-area:dia]" :class="apagado ? 'text-texto-3' : ''">
        <template v-if="compacto">
          <b class="text-base">{{ d.relativo ?? d.rotuloLongo.split(',')[0] }}</b>
          <span class="text-texto-3"> · {{ d.relativo ? d.rotulo.toLowerCase().replace(',', '') : d.rotulo.split(', ')[1] }}</span>
        </template>
        <template v-else>
          <b class="text-base">{{ d.rotulo }}</b>
          <span v-if="d.relativo" class="selo selo-info ml-2 py-0.5!">{{ d.relativo }}</span>
        </template>
      </p>

      <!-- as batidas (ou por que não há) -->
      <div class="min-w-0 [grid-area:bat]">
        <p v-if="!semBatidas" class="text-base">
          <template v-for="(b, i) in d.batidas" :key="b.tipo">
            <span v-if="i" class="text-texto-4"> · </span>
            <span :class="corDaBatida(b)" :title="`${b.rotulo}: ${b.real}${b.nota ? ` (${b.nota})` : ''}`">{{ b.hora }}</span>
          </template>
          <template v-if="d.faltando">
            <span class="text-texto-4"> · </span><span class="font-bold text-negativo">{{ d.faltando }}</span>
          </template>
        </p>
        <p v-else-if="d.marcador" class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
          <span class="selo" :class="d.marcador.feriado ? 'selo-neutro' : 'selo-info'">{{ d.marcador.rotulo }}</span>
          <span v-if="d.descricao" class="text-[0.95rem] text-texto-2">{{ d.descricao }}</span>
        </p>
        <p v-else class="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[0.95rem] text-texto-3">
          <span v-if="mostraEtiqueta" class="selo hidden xl:inline-flex" :class="seloDoTom(d.tom)">{{ d.situacaoTexto }}</span>
          <span>{{ d.descricao }}</span>
        </p>
        <p v-if="!semBatidas && (d.descricao || d.marcador)" class="text-sm text-texto-3">
          <span v-if="d.marcador">{{ d.marcador.rotulo }}{{ d.descricao ? ' · ' : '' }}</span>{{ d.descricao }}
        </p>
        <p v-if="d.lancadoSegundos" class="text-sm text-texto-3">
          Banco de horas neste dia: <b :class="d.lancadoSegundos > 0 ? 'text-positivo' : 'text-negativo'">{{ saldo(d.lancadoSegundos) }}</b>
        </p>
      </div>

      <!-- quanto trabalhou (num dia em andamento o número que anda fica no cartão de hoje, no Início) -->
      <p v-if="!semBatidas" class="hidden [grid-area:trab] xl:block">
        <template v-if="mostraTrabalhado">{{ duracao(d.trabalhadoSegundos) }}</template>
      </p>

      <!-- como o dia ficou -->
      <p class="flex flex-col items-end gap-1 text-right [grid-area:sit] xl:items-start xl:text-left" :class="{ 'xl:hidden': semBatidas }">
        <span v-if="d.divergenciaRh" class="selo selo-atencao">O RH tem diferente</span>
        <span v-else-if="situacaoEhSaldo" class="font-bold whitespace-nowrap" :class="textoDoTom(d.tom)">{{ d.situacaoTexto }}</span>
        <span v-else-if="!semBatidas || mostraEtiqueta" class="selo" :class="seloDoTom(d.tom)">{{ d.situacaoTexto }}</span>
        <span v-if="!semBatidas && mostraTrabalhado" class="text-sm text-texto-3 xl:hidden">trabalhou {{ duracao(d.trabalhadoSegundos) }}</span>
      </p>

      <!-- o que dá para fazer -->
      <div class="flex flex-wrap items-center justify-start gap-2 [grid-area:ac] xl:justify-end">
        <button v-if="d.acoes.corrigir" type="button" class="botao-perigo min-h-11" @click="acao('corrigir')">
          {{ compacto ? 'Corrigir o dia' : 'Corrigir horários' }}
        </button>
        <button v-else-if="d.acoes.conferirRh" type="button" class="botao-escuro min-h-11" @click="acao('conferirRh')">Conferir com o RH</button>
        <template v-else-if="compacto">
          <button v-if="d.acoes.informarBatidas && !d.hoje" type="button" class="botao-linha" @click="acao('abrir')">Resolver</button>
        </template>
        <template v-else>
          <template v-if="d.acoes.informarBatidas">
            <button v-if="d.acoes.marcar && !d.hoje" type="button" class="botao-linha" @click="acao('marcar')">Foi folga ou atestado</button>
            <button type="button" class="botao-linha" @click="acao('informarBatidas')">Informar batidas</button>
          </template>
          <button v-else-if="d.acoes.editarLancamento" type="button" class="botao-linha" @click="acao('editarLancamento')">Editar as horas</button>
          <button v-else-if="d.acoes.ajustar" type="button" class="botao-linha" @click="acao('ajustar')">Ajustar</button>
          <button v-else-if="d.acoes.removerMarcacao" type="button" class="botao-linha" @click="acao('removerMarcacao')">
            Remover {{ artigoMarcacao }} {{ rotuloMarcacao }}
          </button>
          <button v-else-if="d.futuro && d.acoes.marcar" type="button" class="botao-linha" @click="acao('marcar')">Marcar folga ou férias</button>
        </template>
        <button
          v-if="temDetalhe"
          type="button"
          class="botao-linha gap-1!"
          :aria-expanded="aberto"
          :aria-controls="`detalhe-${d.data}`"
          :aria-label="`${aberto ? 'Fechar' : 'Ver'} os detalhes de ${d.rotulo}`"
          @click="$emit('alternar', d)"
        >
          Detalhes
          <Icone nome="abaixo" tamanho="16" class="transition-transform" :class="{ 'rotate-180': aberto }" />
        </button>
      </div>
    </div>

    <!-- Detalhe do dia -->
    <div v-if="aberto && !compacto" :id="`detalhe-${d.data}`" class="mb-4 flex flex-col gap-4 rounded-xl bg-superficie-2 p-4">
      <div v-if="d.batidas.length" class="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
        <div v-for="b in d.batidas" :key="b.tipo" class="rounded-xl border border-borda bg-superficie px-3 py-2.5">
          <p class="text-sm text-texto-3">{{ b.rotulo }}</p>
          <p class="text-xl font-bold" :class="corDaBatida(b)">{{ b.real }}</p>
          <p v-if="b.nota" class="text-sm font-semibold" :class="b.tom === 'NEGATIVO' ? 'text-negativo' : 'text-positivo'">{{ b.nota }}</p>
          <p v-if="b.considerado && b.considerado !== b.real" class="text-sm text-texto-3">na conta entrou {{ b.considerado }}</p>
          <p class="mt-1.5 flex flex-wrap items-center gap-2">
            <button
              v-if="b.comprovanteId"
              type="button"
              class="botao-linha min-h-9! px-2.5! py-1! text-sm"
              :disabled="baixando.has(b.comprovanteId)"
              :aria-label="`Baixar o comprovante da ${b.rotulo} de ${d.rotulo}`"
              @click="$emit('baixar', { id: b.comprovanteId, rotulo: b.rotulo })"
            ><Icone nome="baixar" tamanho="16" /> Comprovante</button>
            <span v-else-if="b.ajustada" class="selo selo-neutro" title="Batida incluída ou corrigida à mão (sem comprovante); o motivo está no histórico abaixo">informada à mão</span>
            <span v-else class="text-sm text-texto-4">sem comprovante</span>
          </p>
        </div>
      </div>

      <dl v-if="d.trabalhadoSegundos !== null" class="flex flex-wrap gap-x-8 gap-y-2 text-[0.95rem]">
        <div><dt class="text-sm text-texto-3">{{ d.situacao === 'EM_ANDAMENTO' ? 'Trabalhado nos períodos já fechados' : 'Trabalhado' }}</dt><dd class="font-bold">{{ duracao(d.trabalhadoSegundos) }}</dd></div>
        <div><dt class="text-sm text-texto-3">Previsto para o dia</dt><dd class="font-bold">{{ d.previstoSegundos ? duracao(d.previstoSegundos) : 'sem jornada' }}</dd></div>
        <div><dt class="text-sm text-texto-3">Como ficou</dt><dd class="font-bold" :class="textoDoTom(d.tom)">{{ d.situacaoTexto }}</dd></div>
      </dl>

      <div v-if="d.lancamentos.length">
        <h4 class="text-sm font-bold text-texto-2">Horas usadas ou somadas no banco neste dia</h4>
        <ul class="mt-1.5 flex flex-col gap-1.5">
          <li v-for="l in d.lancamentos" :key="l.id" class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[0.95rem]">
            <b :class="l.segundos > 0 ? 'text-positivo' : 'text-negativo'">{{ saldo(l.segundos) }}</b>
            <span class="min-w-0 flex-1">{{ l.descricao }} <span class="text-sm text-texto-3">· por {{ l.criadoPor }}</span></span>
            <button v-if="d.acoes.lancarNoBanco" type="button" class="link text-sm" :disabled="salvando" @click="$emit('remover-lancamento', l)">Remover</button>
          </li>
        </ul>
      </div>

      <div v-if="d.ajustes.length">
        <h4 class="text-sm font-bold text-texto-2">Histórico de ajustes à mão</h4>
        <ul class="mt-1.5 flex flex-col gap-2">
          <li v-for="a in d.ajustes" :key="a.id" class="rounded-lg border border-borda bg-superficie px-3 py-2 text-[0.95rem]">
            <p class="text-sm text-texto-3">{{ momento(a.ajustadoEm) }} · por {{ a.usuario }}</p>
            <p>{{ horas(a.antes) }} <span class="text-texto-4">→</span> <b>{{ horas(a.depois) }}</b></p>
            <p class="text-texto-2">Motivo: {{ a.justificativa }}</p>
          </li>
        </ul>
      </div>

      <div v-if="Object.values(d.acoes).some(Boolean) || d.comprovantes.length > 1" class="flex flex-wrap gap-2 border-t border-borda pt-3">
        <button v-if="d.comprovantes.length > 1" type="button" class="botao-linha" @click="$emit('baixar-todos', d)">
          <Icone nome="baixar" tamanho="18" /> Baixar os {{ d.comprovantes.length }} comprovantes
        </button>
        <button v-if="d.acoes.ajustar && !d.acoes.informarBatidas" type="button" class="botao-linha" @click="acao('ajustar')">
          <Icone nome="lapis" tamanho="18" /> {{ d.acoes.corrigir ? 'Corrigir horários' : 'Ajustar os horários' }}
        </button>
        <button v-if="d.acoes.lancarHoras" type="button" class="botao-linha" @click="acao('lancarHoras')">
          <Icone nome="relogio" tamanho="18" /> Lançar horas trabalhadas neste dia
        </button>
        <button v-if="d.acoes.marcar" type="button" class="botao-linha" @click="acao('marcar')">
          <Icone nome="sol" tamanho="18" /> Marcar folga, férias ou feriado
        </button>
        <button v-if="d.acoes.removerMarcacao" type="button" class="botao-linha" @click="acao('removerMarcacao')">
          <Icone nome="fechar" tamanho="18" /> Remover {{ artigoMarcacao }} {{ rotuloMarcacao }}
        </button>
        <button v-if="d.acoes.lancarNoBanco" type="button" class="botao-linha" @click="acao('lancarNoBanco')">
          <Icone nome="somar" tamanho="18" /> Usar ou somar horas do banco
        </button>
        <button v-if="d.acoes.excluir" type="button" class="botao-linha text-negativo!" :disabled="salvando" @click="acao('excluir')">
          <Icone nome="lixeira" tamanho="18" /> Apagar o registro do dia
        </button>
      </div>
    </div>
  </li>
</template>
