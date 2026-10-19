<script setup>
import { computed, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { copiaDiretaDisponivel, copiarPelaSelecao, copiarTextoDireto } from '@/modulos/atendimento/areaDeTransferencia'
import { tamanho, TIPOS_DE_SAIDA } from '@/modulos/atendimento/formato'
import { useAuthStore } from '@/stores/auth'
import { mensagemDe } from '@/utils/erros'
import { salvarBlob, salvarResposta } from '@/utils/download'

/**
 * O texto pelo Gem do Gemini (gemini.google.com), sem usar a cota da API. Uma vez só, a pessoa cria o Gem com as
 * instruções do sistema. Em cada atendimento: baixa o pacote (a conversa e as imagens num PDF, os áudios num MP3, os
 * vídeos à parte), arrasta no Gem e escreve "resumo", "erro" ou "alteração". O Gem responde em texto pronto para o
 * chamado; colar a resposta aqui é opcional (numera as imagens e permite mandar para a base).
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
  /** O tipo que a tela está mostrando (a resposta colada começa por ele). */
  tipoAtual: { type: String, default: null },
})
const emit = defineEmits(['progresso', 'usado'])

const GEMINI = 'https://gemini.google.com/app'
const COMANDOS = [
  { codigo: 'resumo', texto: 'resumo' },
  { codigo: 'dev_erro', texto: 'erro' },
  { codigo: 'dev_alteracao', texto: 'alteração' },
]

const auth = useAuthStore()
const chave = (nome) => `conferencia-ponto.gem.${auth.usuario?.login ?? 'anonimo'}.${nome}`
function lerGuardado(nome) {
  try {
    return localStorage.getItem(chave(nome))
  } catch {
    return null
  }
}
function guardar(nome, valor) {
  try {
    if (valor == null) localStorage.removeItem(chave(nome))
    else localStorage.setItem(chave(nome), String(valor))
    return true
  } catch {
    return false
  }
}

const aberto = ref(false)
const instrucoes = ref(null)
const urlDoGem = ref(lerGuardado('url') ?? '')
const urlDigitada = ref(urlDoGem.value)
const versaoCopiada = ref(Number(lerGuardado('versao') ?? 0))
const configurando = ref(false)
const pacote = ref(null)
const resposta = ref('')
const tipo = ref(props.tipoAtual ?? 'resumo')
const tipoEscolhido = ref(false)
const acao = ref('')
const mensagem = ref(null)

const tipos = computed(() => TIPOS_DE_SAIDA.filter((t) => t.disponivel))
const faltaBaixar = computed(() => (props.atendimento.anexos ?? []).filter((a) => a.situacao === 'aguardando' && !a.vencido).length)
const respostaLonga = computed(() => resposta.value.length > 400_000)
const instrucoesMudaram = computed(() => !!instrucoes.value && versaoCopiada.value > 0 && versaoCopiada.value < instrucoes.value.versao)
const precisaConfigurar = computed(() => !urlDoGem.value || versaoCopiada.value === 0 || instrucoesMudaram.value)
const linkDoGem = computed(() => urlDoGem.value || GEMINI)

async function abrir() {
  aberto.value = !aberto.value
  if (!aberto.value) return
  if (props.tipoAtual && !tipoEscolhido.value) tipo.value = props.tipoAtual
  if (!instrucoes.value) {
    try {
      instrucoes.value = await atendimentoApi.instrucoesDoGem()
    } catch (e) {
      mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
    }
  }
  configurando.value = precisaConfigurar.value
}

async function copiarTexto(texto, ok) {
  mensagem.value = null
  try {
    if (copiaDiretaDisponivel()) await copiarTextoDireto(Promise.resolve(texto))
    else if (!copiarPelaSelecao(texto, 'texto')) throw new Error('cópia recusada')
    mensagem.value = { tipo: 'ok', texto: ok }
    return true
  } catch {
    mensagem.value = { tipo: 'erro', texto: 'O navegador não deixou copiar. Use "Baixar as instruções (.txt)" e copie do arquivo.' }
    return false
  }
}

async function copiarInstrucoes() {
  if (!instrucoes.value) return
  if (await copiarTexto(instrucoes.value.texto, 'Instruções copiadas. Cole no campo Instruções do Gem e salve.')) {
    versaoCopiada.value = instrucoes.value.versao
    guardar('versao', instrucoes.value.versao)
  }
}

function baixarInstrucoes() {
  if (!instrucoes.value) return
  salvarBlob(new Blob([instrucoes.value.texto], { type: 'text/plain;charset=utf-8' }), `instrucoes-do-gem-v${instrucoes.value.versao}.txt`)
  versaoCopiada.value = instrucoes.value.versao
  guardar('versao', instrucoes.value.versao)
}

function guardarUrl() {
  mensagem.value = null
  const url = urlDigitada.value.trim()
  if (url && !/^https:\/\/gemini\.google\.com\//.test(url)) {
    mensagem.value = { tipo: 'erro', texto: 'Cole o endereço do Gem como aparece no navegador (começa com https://gemini.google.com/).' }
    return
  }
  urlDoGem.value = url
  guardar('url', url || null)
  mensagem.value = { tipo: 'ok', texto: url ? 'Endereço do Gem guardado neste navegador.' : 'Endereço do Gem apagado.' }
  if (url && !precisaConfigurar.value) configurando.value = false
}

const espera = (ms) => new Promise((r) => setTimeout(r, ms))

/** Monta o pacote e baixa os arquivos, um depois do outro (o navegador pode pedir para permitir vários downloads). */
async function baixarPacote() {
  acao.value = 'pacote'
  mensagem.value = null
  try {
    pacote.value = await atendimentoApi.pacoteParaOGem(props.atendimento.id)
    for (const arquivo of pacote.value.arquivos) {
      acao.value = `baixar-${arquivo.nome}`
      salvarResposta(await atendimentoApi.arquivoParaOApp(props.atendimento.id, arquivo.nome), arquivo.nome)
      await espera(400)
    }
    mensagem.value = { tipo: 'ok', texto: 'Arquivos baixados. Arraste todos para o Gem e escreva o que quer.' }
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

/** Pelo texto colado, qual dos três ele é (até a pessoa escolher). */
function reconhecerTipo() {
  if (tipoEscolhido.value) return
  const t = resposta.value
  if (/^\s*#*\s*\**\s*(Passos para reproduzir|Relato do problema|Resultado obtido)/im.test(t)) tipo.value = 'dev_erro'
  else if (/(Solicita[çc][ãa]o de Melhoria|^\s*#*\s*\**\s*Descri[çc][ãa]o resumida)/im.test(t)) tipo.value = 'dev_alteracao'
  else if (t.trim()) tipo.value = 'resumo'
}

async function usar() {
  acao.value = 'usar'
  mensagem.value = null
  try {
    const progresso = await atendimentoApi.respostaDoApp(props.atendimento.id, tipo.value, resposta.value)
    emit('progresso', progresso)
    emit('usado', tipo.value)
    resposta.value = ''
    tipoEscolhido.value = false
    aberto.value = false
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}
</script>

<template>
  <section class="mt-4 border-t border-linha pt-3 text-sm" aria-label="Pelo Gem do Gemini">
    <button type="button" class="flex items-baseline gap-2 text-left font-semibold" :aria-expanded="aberto" @click="abrir">
      <span aria-hidden="true">{{ aberto ? '▾' : '▸' }}</span>
      <span>Gerar pelo Gem do Gemini <span class="font-normal text-tinta-suave">(sem usar a cota da API)</span></span>
    </button>

    <div v-if="aberto" class="mt-3 grid gap-4">
      <p class="text-tinta-suave">
        O Gem é um Gemini com as instruções do sistema guardadas: você baixa os arquivos do atendimento, arrasta no Gem e
        escreve o que quer. A resposta vem pronta para o chamado.
      </p>

      <div class="rounded-[3px] border border-linha">
        <button
          type="button"
          class="flex w-full items-baseline gap-2 px-3 py-2 text-left"
          :aria-expanded="configurando"
          @click="configurando = !configurando"
        >
          <span aria-hidden="true">{{ configurando ? '▾' : '▸' }}</span>
          <span class="font-semibold">Criar o Gem</span>
          <span class="text-xs text-tinta-suave">(uma vez só)</span>
          <span v-if="instrucoesMudaram" class="ml-auto text-xs font-semibold text-carimbo">as instruções mudaram</span>
          <span v-else-if="!precisaConfigurar" class="ml-auto text-xs text-credito">pronto</span>
        </button>
        <div v-if="configurando" class="grid gap-3 border-t border-linha px-3 py-3">
          <p v-if="instrucoesMudaram" role="alert" class="text-carimbo">
            As instruções do Gem mudaram (versão {{ instrucoes.versao }}): copie de novo, cole no seu Gem no lugar das
            antigas e salve.
          </p>
          <ol class="grid list-decimal gap-2 pl-5">
            <li>
              No <a class="underline underline-offset-4" :href="GEMINI" target="_blank" rel="noopener noreferrer">gemini.google.com</a>,
              no computador, abra <span class="font-semibold">Explorar Gems</span> → <span class="font-semibold">Novo Gem</span>
              e dê um nome (ex.: Atendimentos).
            </li>
            <li>
              <div class="grid gap-2">
                <span>Copie as instruções, cole no campo <span class="font-semibold">Instruções</span> do Gem e clique em Salvar.</span>
                <span class="flex flex-wrap gap-2">
                  <button type="button" class="botao-primario" :disabled="!instrucoes" @click="copiarInstrucoes">Copiar as instruções</button>
                  <button type="button" class="botao-secundario" :disabled="!instrucoes" @click="baixarInstrucoes">Baixar as instruções (.txt)</button>
                </span>
                <details v-if="instrucoes">
                  <summary class="cursor-pointer text-xs text-tinta-suave">Ver as instruções (versão {{ instrucoes.versao }})</summary>
                  <textarea class="campo mt-2 h-40 w-full font-mono text-xs" readonly :value="instrucoes.texto" aria-label="As instruções do Gem" />
                </details>
              </div>
            </li>
            <li>
              <div class="grid gap-2">
                <label for="url-do-gem">Com o Gem aberto, copie o endereço da barra do navegador e cole aqui (o botão Abrir o meu Gem vai direto para ele):</label>
                <span class="flex flex-wrap gap-2">
                  <input
                    id="url-do-gem"
                    v-model="urlDigitada"
                    type="url"
                    class="campo min-w-0 flex-1"
                    placeholder="https://gemini.google.com/gem/..."
                    @keydown.enter.prevent="guardarUrl"
                  />
                  <button type="button" class="botao-secundario" @click="guardarUrl">Guardar</button>
                </span>
              </div>
            </li>
          </ol>
        </div>
      </div>

      <p v-if="faltaBaixar" class="text-carimbo">
        {{ faltaBaixar === 1 ? '1 anexo ainda não foi baixado' : `${faltaBaixar} anexos ainda não foram baixados` }}: clique em
        Processar (os downloads não usam a cota do Gemini) e baixe os arquivos depois, senão o texto sai sem eles.
      </p>

      <ol class="grid gap-4 pl-0">
        <li class="grid gap-2">
          <p><span class="font-semibold">1. Baixe os arquivos do atendimento</span></p>
          <div>
            <button type="button" class="botao-primario" :disabled="!!acao" @click="baixarPacote">
              {{ acao === 'pacote' ? 'Montando…' : acao.startsWith('baixar-') ? 'Baixando…' : pacote ? 'Baixar de novo' : 'Baixar os arquivos' }}
            </button>
          </div>
          <p class="text-xs text-tinta-suave">
            Se o navegador perguntar se deixa baixar vários arquivos, clique em Permitir.
          </p>
          <div v-if="pacote?.avisos.length" role="note" class="rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2">
            <p class="font-semibold text-carimbo">Confira antes de mandar</p>
            <ul class="mt-1 list-disc pl-5">
              <li v-for="(aviso, i) in pacote.avisos" :key="i">{{ aviso }}</li>
            </ul>
          </div>
          <ul v-if="pacote?.arquivos.length" class="grid gap-1.5">
            <li v-for="a in pacote.arquivos" :key="a.nome" class="grid gap-0.5 rounded-[2px] border border-linha px-2 py-1.5">
              <div class="flex flex-wrap items-baseline gap-x-3">
                <span class="carimbo min-w-0 break-all font-semibold">{{ a.nome }}</span>
                <span class="text-xs text-tinta-suave">{{ tamanho(a.tamanho) }}</span>
                <button type="button" class="ml-auto text-xs underline underline-offset-4" :disabled="!!acao" @click="baixar(a)">
                  {{ acao === `baixar-${a.nome}` ? 'Baixando…' : 'Baixar' }}
                </button>
              </div>
              <p v-if="a.conteudo" class="text-xs text-tinta-suave">{{ a.conteudo }}</p>
            </li>
          </ul>
        </li>

        <li class="grid gap-2">
          <p>
            <span class="font-semibold">2. No Gem, arraste os arquivos e escreva o que quer:</span>
            <template v-for="(c, i) in COMANDOS" :key="c.codigo">
              {{ i ? (i === COMANDOS.length - 1 ? ' ou ' : ', ') : ' ' }}<span class="carimbo font-semibold">{{ c.texto }}</span>
            </template>.
            A resposta vem pronta para colar no chamado; para os dados da base, escreva <span class="carimbo font-semibold">base</span> depois.
          </p>
          <div>
            <a class="botao-secundario inline-block" :href="linkDoGem" target="_blank" rel="noopener noreferrer">
              {{ urlDoGem ? 'Abrir o meu Gem' : 'Abrir o Gemini' }}
            </a>
          </div>
        </li>

        <li class="grid gap-2">
          <label for="resposta-do-gem">
            <span class="font-semibold">3. Opcional: cole aqui a resposta do Gem</span>
            <span class="text-tinta-suave"> para numerar as imagens (Imagem_01...) e mandar para a base de conhecimento</span>
          </label>
          <textarea
            id="resposta-do-gem"
            v-model="resposta"
            class="campo h-40 w-full text-sm"
            placeholder="Assunto: Digisac: ... &#10;&#10;O texto do Gem, inteiro (cole a resposta de base junto, se pediu)."
            @input="reconhecerTipo"
          />
          <fieldset class="flex flex-wrap items-center gap-x-4 gap-y-2">
            <legend class="rotulo mb-1">É o texto de</legend>
            <label v-for="t in tipos" :key="t.codigo" class="flex items-center gap-1.5">
              <input v-model="tipo" type="radio" name="tipo-do-gem" :value="t.codigo" :disabled="!!acao" @change="tipoEscolhido = true" />
              {{ t.rotulo }}
            </label>
          </fieldset>
          <p v-if="respostaLonga" class="text-xs text-carimbo">A resposta passa de 400 mil caracteres: confira se colou só a resposta.</p>
          <div>
            <button type="button" class="botao-primario" :disabled="!!acao || !resposta.trim() || respostaLonga" @click="usar">
              {{ acao === 'usar' ? 'Conferindo…' : 'Usar a resposta' }}
            </button>
          </div>
        </li>
      </ol>

      <p
        v-if="mensagem"
        :role="mensagem.tipo === 'erro' ? 'alert' : 'status'"
        :class="mensagem.tipo === 'erro' ? 'text-carimbo' : 'text-credito'"
      >{{ mensagem.texto }}</p>
    </div>
  </section>
</template>
