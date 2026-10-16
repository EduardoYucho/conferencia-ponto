<script setup>
import { computed, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { copiaDiretaDisponivel, copiarPelaSelecao, copiarTextoDireto } from '@/modulos/atendimento/areaDeTransferencia'
import { useAuthStore } from '@/stores/auth'
import { mensagemDe } from '@/utils/erros'
import { salvarBlob, salvarResposta } from '@/utils/download'

/**
 * O texto pelo app do Gemini, com a habilidade do sistema, sem usar a cota da API: a pessoa escolhe o texto e um
 * clique baixa os arquivos do atendimento, copia o comando e abre o Gemini. Lá, ela arrasta os arquivos e cola o
 * comando; a resposta vem pronta para o chamado. Colar a resposta aqui é opcional (numera as imagens e permite mandar
 * para a base).
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
  /** O tipo que a tela está mostrando (começa por ele). */
  tipoAtual: { type: String, default: null },
})
const emit = defineEmits(['progresso', 'usado'])

const GEMINI = 'https://gemini.google.com/app'
const OPCOES = [
  { codigo: 'resumo', rotulo: 'Resumo', comando: 'resumo' },
  { codigo: 'dev_erro', rotulo: 'Erro', comando: 'erro' },
  { codigo: 'dev_alteracao', rotulo: 'Alteração', comando: 'alteração' },
]

const auth = useAuthStore()
const chave = () => `conferencia-ponto.habilidade.${auth.usuario?.login ?? 'anonimo'}.versao`
function versaoGuardada() {
  try {
    return Number(localStorage.getItem(chave()) ?? 0)
  } catch {
    return 0
  }
}

const aberto = ref(false)
const tipo = ref(props.tipoAtual ?? 'resumo')
const habilidade = ref(null)
const versaoBaixada = ref(versaoGuardada())
const pacote = ref(null)
const comandoCopiado = ref('')
const colando = ref(false)
const resposta = ref('')
const acao = ref('')
const mensagem = ref(null)

const opcao = computed(() => OPCOES.find((o) => o.codigo === tipo.value) ?? OPCOES[0])
const nomeDaHabilidade = computed(() => habilidade.value?.nome ?? 'resumo-de-atendimento')
const comando = computed(() => `Use a habilidade ${nomeDaHabilidade.value}: ${opcao.value.comando}`)
const habilidadeDesatualizada = computed(() => !!habilidade.value && versaoBaixada.value < habilidade.value.versao)
const faltaBaixar = computed(() => (props.atendimento.anexos ?? []).filter((a) => a.situacao === 'aguardando' && !a.vencido).length)
const respostaLonga = computed(() => resposta.value.length > 400_000)

async function abrir() {
  aberto.value = !aberto.value
  if (!aberto.value) return
  if (props.tipoAtual && !acao.value) tipo.value = props.tipoAtual
  if (!habilidade.value) {
    try {
      habilidade.value = await atendimentoApi.habilidadeDoGemini()
    } catch {
      // sem a habilidade, o nome padrão serve para o comando
    }
  }
}

function escolher(codigo) {
  tipo.value = codigo
  pacote.value = null
  comandoCopiado.value = ''
  mensagem.value = null
}

/** Copia o comando dentro do próprio clique (o navegador só deixa copiar assim). */
function copiarComando() {
  const texto = comando.value
  try {
    if (copiaDiretaDisponivel()) {
      copiarTextoDireto(Promise.resolve(texto)).catch(() => {})
    } else if (!copiarPelaSelecao(texto, 'texto')) {
      return false
    }
    comandoCopiado.value = texto
    return true
  } catch {
    return false
  }
}

const espera = (ms) => new Promise((r) => setTimeout(r, ms))

/** Um clique: copia o comando, abre o Gemini numa aba nova e baixa os arquivos do atendimento. */
async function irParaOGemini() {
  mensagem.value = null
  const copiou = copiarComando()
  window.open(GEMINI, '_blank', 'noopener')
  acao.value = 'pacote'
  try {
    pacote.value = await atendimentoApi.pacoteParaOGemini(props.atendimento.id)
    for (const arquivo of pacote.value.arquivos) {
      acao.value = 'baixando'
      salvarResposta(await atendimentoApi.arquivoParaOApp(props.atendimento.id, arquivo.nome), arquivo.nome)
      await espera(400)
    }
    if (!copiou) {
      mensagem.value = { tipo: 'erro', texto: `O navegador não deixou copiar o comando. No Gemini, escreva: ${comando.value}` }
    }
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

async function baixar(arquivo) {
  acao.value = `baixar-${arquivo.nome}`
  mensagem.value = null
  try {
    salvarResposta(await atendimentoApi.arquivoParaOApp(props.atendimento.id, arquivo.nome), arquivo.nome)
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

function baixarHabilidade() {
  if (!habilidade.value) return
  salvarBlob(new Blob([habilidade.value.skillMd], { type: 'text/markdown;charset=utf-8' }), 'SKILL.md')
  versaoBaixada.value = habilidade.value.versao
  try {
    localStorage.setItem(chave(), String(habilidade.value.versao))
  } catch {
    // sem armazenamento: o aviso volta na próxima vez
  }
}

async function usar() {
  acao.value = 'usar'
  mensagem.value = null
  try {
    const progresso = await atendimentoApi.respostaDoApp(props.atendimento.id, tipo.value, resposta.value)
    emit('progresso', progresso)
    emit('usado', tipo.value)
    resposta.value = ''
    colando.value = false
    aberto.value = false
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}
</script>

<template>
  <section class="mt-4 border-t border-linha pt-3 text-sm" aria-label="Pelo app do Gemini">
    <button type="button" class="flex items-baseline gap-2 text-left font-semibold" :aria-expanded="aberto" @click="abrir">
      <span aria-hidden="true">{{ aberto ? '▾' : '▸' }}</span>
      <span>Gerar pelo app do Gemini <span class="font-normal text-tinta-suave">(sem usar a cota da API)</span></span>
    </button>

    <div v-if="aberto" class="mt-3 grid gap-3">
      <div class="flex flex-wrap items-center gap-2" role="radiogroup" aria-label="O que gerar">
        <button
          v-for="o in OPCOES"
          :key="o.codigo"
          type="button"
          role="radio"
          :aria-checked="tipo === o.codigo"
          class="rounded-[3px] border px-4 py-2 font-semibold"
          :class="tipo === o.codigo ? 'border-tinta bg-tinta text-papel' : 'border-linha bg-cartao hover:border-tinta'"
          :disabled="!!acao"
          @click="escolher(o.codigo)"
        >{{ o.rotulo }}</button>
      </div>

      <p v-if="faltaBaixar" class="text-carimbo">
        {{ faltaBaixar === 1 ? '1 anexo ainda não foi baixado' : `${faltaBaixar} anexos ainda não foram baixados` }}: clique em
        Processar antes, senão o texto sai sem eles.
      </p>

      <div>
        <button type="button" class="botao-primario" :disabled="!!acao" @click="irParaOGemini">
          {{ acao === 'pacote' ? 'Montando os arquivos…' : acao === 'baixando' ? 'Baixando…' : 'Baixar os arquivos e abrir o Gemini' }}
        </button>
      </div>

      <div v-if="pacote && !acao" role="status" class="grid gap-1 rounded-[3px] border border-credito/40 bg-credito/5 px-3 py-2">
        <p>
          No Gemini, arraste {{ pacote.arquivos.length === 1 ? 'o arquivo baixado' : `os ${pacote.arquivos.length} arquivos baixados` }}
          <template v-if="comandoCopiado">e cole com <span class="font-semibold">Ctrl+V</span> (o comando já está copiado)</template>.
        </p>
        <p class="text-xs text-tinta-suave">
          <template v-for="(a, i) in pacote.arquivos" :key="a.nome">{{ i ? ' · ' : '' }}<button
            type="button"
            class="underline underline-offset-4"
            :disabled="!!acao"
            :title="`Baixar ${a.nome} de novo`"
            @click="baixar(a)"
          >{{ a.nome }}</button></template>
        </p>
        <ul v-if="pacote.avisos.length" class="list-disc pl-5 text-xs text-carimbo">
          <li v-for="(aviso, i) in pacote.avisos" :key="i">{{ aviso }}</li>
        </ul>
      </div>

      <div>
        <button type="button" class="text-xs underline underline-offset-4" :aria-expanded="colando" @click="colando = !colando">
          {{ colando ? 'Fechar' : 'Colar a resposta aqui' }}
        </button>
        <span class="text-xs text-tinta-suave"> (opcional: numera as imagens e manda para a base)</span>
        <div v-if="colando" class="mt-2 grid gap-2">
          <label for="resposta-do-gemini" class="sr-only">A resposta do Gemini</label>
          <textarea id="resposta-do-gemini" v-model="resposta" class="campo h-40 w-full text-sm" placeholder="Cole aqui a resposta inteira do Gemini." />
          <p v-if="respostaLonga" class="text-xs text-carimbo">A resposta passa de 400 mil caracteres: confira se colou só a resposta.</p>
          <div>
            <button type="button" class="botao-secundario" :disabled="!!acao || !resposta.trim() || respostaLonga" @click="usar">
              {{ acao === 'usar' ? 'Conferindo…' : `Usar como ${opcao.rotulo.toLowerCase()}` }}
            </button>
          </div>
        </div>
      </div>

      <p
        v-if="mensagem"
        :role="mensagem.tipo === 'erro' ? 'alert' : 'status'"
        :class="mensagem.tipo === 'erro' ? 'text-carimbo' : 'text-credito'"
      >{{ mensagem.texto }}</p>

      <p class="border-t border-linha pt-2 text-xs text-tinta-suave">
        <template v-if="habilidadeDesatualizada">
          <span class="font-semibold text-carimbo">Atualize a habilidade no Gemini</span> (versão {{ habilidade.versao }}):
          baixe o SKILL.md e envie de novo em Configurações → Habilidades.
        </template>
        <template v-else>Habilidade no Gemini: {{ nomeDaHabilidade }}<template v-if="habilidade"> (versão {{ habilidade.versao }})</template>.</template>
        <button v-if="habilidade" type="button" class="ml-1 underline underline-offset-4" @click="baixarHabilidade">Baixar o SKILL.md</button>
      </p>
    </div>
  </section>
</template>
