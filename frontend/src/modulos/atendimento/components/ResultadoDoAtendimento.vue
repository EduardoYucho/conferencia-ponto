<script setup>
import { computed, ref, watch } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { mensagemDe } from '@/utils/erros'
import {
  camposDaTela,
  mesmosCampos,
  paraODesenvolvimento,
  problemasDosCampos,
  SITUACOES_DA_SAIDA,
  TIPOS_DE_SAIDA,
} from '@/modulos/atendimento/formato'
import CamposDoChamado from './CamposDoChamado.vue'
import SaidaDoAtendimento from './SaidaDoAtendimento.vue'

/**
 * Os textos gerados do atendimento, uma aba por tipo (o resumo e os chamados de erro e de alteração). Enquanto o
 * texto é escrito, a aba mostra a situação ao vivo; pronto, abre o texto com o assunto, a prévia, as imagens e a
 * cópia. Os tipos que faltam podem ser pedidos aqui; os chamados pedem antes os dados que não estão em nenhuma fonte.
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
})
const emit = defineEmits(['progresso', 'campos', 'confirmada'])

const aba = ref(null)
const acao = ref('')
const mensagem = ref('')
/** O chamado que a pessoa quer pedir: abre os dados antes de pôr na fila. */
const pedindo = ref(null)
const campos = ref(camposDaTela(null))

const saidas = computed(() => props.atendimento.saidas ?? [])
const atual = computed(() => saidas.value.find((s) => s.tipo === aba.value) ?? saidas.value[0] ?? null)
const processando = computed(() => ['processando', 'pausado'].includes(props.atendimento.situacao))
/** Anexos que ainda não foram baixados: gerar um texto baixa e analisa antes. */
const faltaBaixar = computed(() => (props.atendimento.anexos ?? []).some((a) => a.situacao === 'aguardando' && !a.vencido))
const faltam = computed(() => TIPOS_DE_SAIDA.filter((t) => t.disponivel && !saidas.value.some((s) => s.tipo === t.codigo)))
const camposComProblema = computed(() => problemasDosCampos(campos.value).length > 0)

watch(saidas, (lista) => {
  if (!lista.some((s) => s.tipo === aba.value)) aba.value = lista[0]?.tipo ?? null
}, { immediate: true })

function tipoDe(codigo) {
  return TIPOS_DE_SAIDA.find((t) => t.codigo === codigo)
}

function rotulo(codigo) {
  return tipoDe(codigo)?.rotulo ?? codigo
}

const espera = computed(() => {
  const s = atual.value
  if (!s) return ''
  if (props.atendimento.situacao === 'pausado') return props.atendimento.motivoPausa ?? 'O processamento está pausado.'
  if (props.atendimento.situacao === 'cancelado') return 'O processamento foi cancelado. Clique em Processar para continuar.'
  if (s.situacao === 'redigindo') return 'Escrevendo o texto com o Gemini…'
  return s.erro ?? 'O texto é escrito quando os downloads e as análises terminarem.'
})

/** O resumo vai direto para a fila; os chamados abrem antes os dados do cabeçalho e do rodapé. */
function pedir(codigo) {
  mensagem.value = ''
  if (paraODesenvolvimento(codigo)) {
    campos.value = camposDaTela(props.atendimento.campos)
    pedindo.value = codigo
    return
  }
  gerar(codigo)
}

async function gerar(codigo) {
  acao.value = 'gerar'
  mensagem.value = ''
  try {
    if (paraODesenvolvimento(codigo) && !mesmosCampos(campos.value, props.atendimento.campos)) {
      emit('campos', await atendimentoApi.salvarCampos(props.atendimento.id, campos.value))
    }
    const progresso = await atendimentoApi.processar(props.atendimento.id, [codigo])
    pedindo.value = null
    aba.value = codigo
    emit('progresso', progresso)
  } catch (e) {
    mensagem.value = mensagemDe(e)
  } finally {
    acao.value = ''
  }
}

async function gerarDeNovo(tipo) {
  acao.value = 'gerar'
  mensagem.value = ''
  try {
    emit('progresso', await atendimentoApi.gerarDeNovo(props.atendimento.id, tipo))
  } catch (e) {
    mensagem.value = mensagemDe(e)
  } finally {
    acao.value = ''
  }
}
</script>

<template>
  <section class="cartao px-5 py-4" aria-label="Textos gerados">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="rotulo">Textos gerados</h2>
      <div v-if="saidas.length > 1" role="tablist" class="flex flex-wrap gap-1">
        <button
          v-for="s in saidas"
          :key="s.tipo"
          type="button"
          role="tab"
          :aria-selected="s.tipo === atual?.tipo"
          class="rounded-[2px] border px-2 py-1 text-xs font-semibold"
          :class="s.tipo === atual?.tipo ? 'border-tinta bg-tinta text-cartao' : 'border-linha'"
          @click="aba = s.tipo"
        >{{ rotulo(s.tipo) }}</button>
      </div>
    </div>

    <div v-if="!saidas.length" class="mt-2 grid gap-3">
      <p class="text-sm text-tinta-suave">
        Nenhum texto pedido ainda. Cada texto é escrito pelo Gemini a partir da conversa e da análise de cada arquivo<span
          v-if="faltaBaixar">; os anexos que faltam são baixados e analisados antes</span>.
      </p>
      <div v-if="!processando && !pedindo" class="flex flex-wrap gap-2">
        <button
          v-for="(tipo, i) in faltam"
          :key="tipo.codigo"
          type="button"
          :class="i === 0 ? 'botao-primario' : 'botao-secundario'"
          :disabled="!!acao"
          @click="pedir(tipo.codigo)"
        >{{ acao && tipo.codigo === 'resumo' ? 'Pedindo…' : `Gerar ${tipo.curto}` }}</button>
      </div>
    </div>

    <template v-else-if="atual">
      <p class="mt-1 flex flex-wrap items-center gap-x-2 text-sm">
        <span class="font-semibold">{{ rotulo(atual.tipo) }}</span>
        <span class="text-xs text-tinta-suave">versão {{ atual.versao }}</span>
        <span class="text-xs" :class="SITUACOES_DA_SAIDA[atual.situacao]?.classe">{{ SITUACOES_DA_SAIDA[atual.situacao]?.rotulo ?? atual.situacao }}</span>
      </p>

      <div v-if="atual.situacao === 'pendente' || atual.situacao === 'redigindo'" class="mt-3" role="status">
        <p class="text-sm" :class="atendimento.situacao === 'pausado' ? 'font-semibold text-carimbo' : 'text-tinta-suave'">{{ espera }}</p>
        <div v-if="atendimento.situacao === 'processando'" class="mt-2 h-1.5 overflow-hidden rounded-full bg-linha" aria-hidden="true">
          <div class="h-full w-1/3 animate-pulse bg-tinta" />
        </div>
      </div>

      <div v-else-if="atual.situacao === 'falhou'" class="mt-3 flex flex-wrap items-center justify-between gap-3">
        <p role="alert" class="text-sm text-carimbo">{{ atual.erro ?? 'Não foi possível escrever o texto.' }}</p>
        <button type="button" class="botao-primario" :disabled="!!acao || processando" @click="gerarDeNovo(atual.tipo)">
          {{ acao ? 'Pedindo…' : 'Gerar de novo' }}
        </button>
      </div>

      <SaidaDoAtendimento
        v-else
        :key="atual.id"
        class="mt-3"
        :atendimento-id="atendimento.id"
        :saida-id="atual.id"
        :campos="atendimento.campos"
        @progresso="emit('progresso', $event)"
        @campos="emit('campos', $event)"
        @confirmada="emit('confirmada', $event)"
      />

      <p v-if="faltam.length && !processando && !pedindo" class="mt-4 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-linha pt-3 text-sm">
        <span class="text-xs text-tinta-suave">Gerar também:</span>
        <button
          v-for="tipo in faltam"
          :key="tipo.codigo"
          type="button"
          class="font-semibold underline underline-offset-4 disabled:opacity-40"
          :disabled="!!acao"
          @click="pedir(tipo.codigo)"
        >{{ tipo.curto }}</button>
      </p>
    </template>

    <!-- os dados do chamado antes de pedir -->
    <div v-if="pedindo" role="region" :aria-label="`Pedir ${tipoDe(pedindo)?.curto}`" class="mt-4 rounded-[3px] border border-tinta/40 bg-papel-escuro/30 px-4 py-3">
      <p class="font-semibold">Gerar {{ tipoDe(pedindo)?.curto }}</p>
      <p class="mt-0.5 mb-3 text-xs text-tinta-suave">
        {{ tipoDe(pedindo)?.descricao }} Os dados abaixo não estão na conversa: saem no cabeçalho e no rodapé e valem
        para os dois chamados (dá para mudar depois, sem gerar de novo).
      </p>
      <CamposDoChamado v-model="campos" prefixo="pedir" :desligado="!!acao" />
      <div class="mt-3 flex flex-wrap gap-2">
        <button type="button" class="botao-primario" :disabled="!!acao || camposComProblema" @click="gerar(pedindo)">
          {{ acao ? 'Pedindo…' : `Gerar ${tipoDe(pedindo)?.curto}` }}
        </button>
        <button type="button" class="botao-secundario" :disabled="!!acao" @click="pedindo = null">Cancelar</button>
      </div>
    </div>

    <p v-if="mensagem" role="alert" class="mt-3 text-sm text-carimbo">{{ mensagem }}</p>
  </section>
</template>
