<script setup>
import { computed, onMounted, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'

/**
 * A chave da API do Gemini da pessoa logada. Cada um usa a própria; o servidor testa com o Google antes de
 * guardar, guarda cifrada e nunca devolve a chave (aqui aparecem só os 4 últimos caracteres). As regras
 * (formato, confirmação do nível pago, o que o Google respondeu) são todas do servidor: a tela mostra o que ele diz.
 */
const estado = ref(null)
const carregando = ref(false)
const erro = ref(null)

/** Formulário de cadastro: aberto quando não há chave ou quando a pessoa pede para trocar. */
const trocando = ref(false)
const chave = ref('')
const nivelPago = ref(false)
const enviando = ref(false)
const erroDoCadastro = ref('')

/** 'testar' | 'apagar' em andamento */
const acao = ref('')
const confirmandoApagar = ref(false)
/** { tipo: 'ok' | 'aviso' | 'erro', texto } — resultado da última ação, na tela até a próxima */
const resultado = ref(null)

const SITUACOES = {
  valida: { rotulo: 'Válida', classe: 'border-credito/40 bg-credito/5 text-credito' },
  sem_cota: { rotulo: 'Sem cota agora', classe: 'border-carimbo/40 bg-carimbo/5 text-carimbo' },
  recusada: { rotulo: 'Recusada pelo Google', classe: 'border-carimbo/40 bg-carimbo/5 text-carimbo' },
  nao_testada: { rotulo: 'Não testada', classe: 'border-linha text-tinta-suave' },
}

const formularioAberto = computed(() => !!estado.value && (!estado.value.cadastrada || trocando.value))
const situacao = computed(() => SITUACOES[estado.value?.situacao] ?? SITUACOES.nao_testada)

async function carregar() {
  carregando.value = true
  erro.value = null
  try {
    estado.value = await atendimentoApi.chaveGemini()
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)

function quando(iso) {
  return iso ? new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—'
}

function tipoDoResultado(view) {
  return view.situacao === 'valida' ? 'ok' : view.situacao === 'sem_cota' ? 'aviso' : 'erro'
}

async function cadastrar() {
  erroDoCadastro.value = ''
  resultado.value = null
  enviando.value = true
  try {
    const view = await atendimentoApi.salvarChaveGemini(chave.value, nivelPago.value)
    estado.value = view
    chave.value = ''
    nivelPago.value = false
    trocando.value = false
    resultado.value = { tipo: tipoDoResultado(view), texto: `Chave guardada. ${view.mensagem ?? ''}`.trim() }
  } catch (e) {
    erroDoCadastro.value = mensagemDe(e)
  } finally {
    enviando.value = false
  }
}

async function testar() {
  resultado.value = null
  acao.value = 'testar'
  try {
    const view = await atendimentoApi.testarChaveGemini()
    estado.value = view
    resultado.value = { tipo: tipoDoResultado(view), texto: view.mensagem ?? 'Chave testada.' }
  } catch (e) {
    resultado.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

async function apagar() {
  resultado.value = null
  acao.value = 'apagar'
  try {
    estado.value = await atendimentoApi.apagarChaveGemini()
    confirmandoApagar.value = false
    trocando.value = false
    resultado.value = { tipo: 'ok', texto: 'Chave apagada do servidor.' }
  } catch (e) {
    resultado.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

function abrirTroca() {
  resultado.value = null
  confirmandoApagar.value = false
  trocando.value = true
}

function cancelarTroca() {
  trocando.value = false
  chave.value = ''
  nivelPago.value = false
  erroDoCadastro.value = ''
}
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <RouterLink :to="{ name: 'atendimentos' }" class="rotulo hover:text-tinta">← Atendimentos</RouterLink>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">
        Minha chave do Gemini
      </h1>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="carregando" :erro="erro" carregando-texto="Consultando a sua chave…" @tentar="carregar">
        <div v-if="estado" class="grid gap-5">
          <p
            v-if="resultado"
            :role="resultado.tipo === 'erro' ? 'alert' : 'status'"
            class="rounded-[3px] border px-3 py-2 text-sm"
            :class="{
              'border-credito/40 bg-credito/5 text-credito': resultado.tipo === 'ok',
              'border-carimbo/40 bg-carimbo/5 text-carimbo': resultado.tipo !== 'ok',
            }"
          >{{ resultado.texto }}</p>

          <!-- chave guardada -->
          <section v-if="estado.cadastrada" class="cartao px-5 py-4" aria-label="Chave guardada">
            <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
              <p class="font-mono text-lg tracking-wider">••••{{ estado.ultimosCaracteres }}</p>
              <span class="rounded-[2px] border px-1.5 py-0.5 text-xs font-bold tracking-wide uppercase" :class="situacao.classe">
                {{ situacao.rotulo }}
              </span>
            </div>
            <dl class="mt-3 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
              <div><dt class="inline text-tinta-suave">Último teste: </dt><dd class="inline">{{ quando(estado.testadaEm) }}</dd></div>
              <div><dt class="inline text-tinta-suave">Cadastrada em: </dt><dd class="inline">{{ quando(estado.atualizadaEm) }}</dd></div>
              <div class="sm:col-span-2">
                <dt class="inline text-tinta-suave">Nível pago confirmado: </dt>
                <dd class="inline">{{ estado.nivelPagoConfirmado ? 'sim' : 'não' }}</dd>
              </div>
            </dl>

            <div v-if="!trocando" class="mt-4 flex flex-wrap items-center gap-2">
              <button type="button" class="botao-primario" :disabled="!!acao" @click="testar">
                {{ acao === 'testar' ? 'Testando com o Google…' : 'Testar de novo' }}
              </button>
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="abrirTroca">Trocar a chave</button>
              <template v-if="!confirmandoApagar">
                <button type="button" class="botao-secundario" :disabled="!!acao" @click="confirmandoApagar = true">Apagar</button>
              </template>
              <span v-else class="flex flex-wrap items-center gap-2 text-sm" role="group" aria-label="Confirmar apagar a chave">
                <span>Apagar a chave do servidor?</span>
                <button type="button" class="botao-secundario border-carimbo! text-carimbo!" :disabled="!!acao" @click="apagar">
                  {{ acao === 'apagar' ? 'Apagando…' : 'Apagar' }}
                </button>
                <button type="button" class="botao-secundario" :disabled="!!acao" @click="confirmandoApagar = false">Cancelar</button>
              </span>
            </div>
          </section>

          <!-- cadastro ou troca -->
          <section v-if="formularioAberto" class="cartao px-5 py-4" aria-label="Cadastrar a chave">
            <h2 class="font-sans text-lg font-bold">{{ estado.cadastrada ? 'Trocar a chave' : 'Cadastrar a sua chave' }}</h2>
            <p v-if="!estado.cadastrada" class="mt-1 text-sm text-tinta-suave">
              O gerador de atendimentos usa a sua própria chave da API do Gemini. Ninguém mais usa a sua, nem você a de outra pessoa.
            </p>
            <form class="mt-3 grid gap-3" @submit.prevent="cadastrar">
              <label class="grid gap-1">
                <span class="rotulo">Chave da API do Gemini</span>
                <input
                  v-model="chave"
                  type="password"
                  name="chave-gemini"
                  class="campo"
                  autocomplete="off"
                  spellcheck="false"
                  placeholder="AQ.… ou AIza…"
                  :disabled="enviando"
                />
              </label>
              <label v-if="estado.exigirNivelPago" class="flex items-start gap-2 text-sm">
                <input v-model="nivelPago" type="checkbox" class="mt-0.5 size-4 accent-tinta" :disabled="enviando" />
                <span>A chave é de um projeto com <b>faturamento ativo (nível pago)</b> no Google. <span class="text-tinta-suave">(veja o porquê abaixo)</span></span>
              </label>
              <p v-if="erroDoCadastro" role="alert" class="text-sm text-carimbo">{{ erroDoCadastro }}</p>
              <div class="flex flex-wrap gap-2">
                <button type="submit" class="botao-primario" :disabled="enviando || !chave.trim()">
                  {{ enviando ? 'Testando com o Google…' : 'Testar e guardar' }}
                </button>
                <button v-if="estado.cadastrada" type="button" class="botao-secundario" :disabled="enviando" @click="cancelarTroca">
                  Cancelar
                </button>
              </div>
            </form>
          </section>

          <section class="text-sm leading-relaxed text-tinta-suave" aria-label="Sobre a chave">
            <h2 class="rotulo text-tinta">Antes de cadastrar</h2>
            <ul class="mt-2 grid list-disc gap-2 pl-5">
              <li>
                <b class="text-tinta">A assinatura do Gemini não inclui a API.</b> O plano do aplicativo vale para o app e para o
                site; a API é cobrada à parte, pelo uso, num projeto do Google Cloud com faturamento.
              </li>
              <li>
                <b class="text-tinta">Onde criar:</b> no Google AI Studio (aistudio.google.com), em "Chaves de API" ("Get API
                key"), num projeto com faturamento ativo: na coluna "Nível de faturamento", o projeto não pode estar em "Nível
                gratuito" (use "Configurar faturamento"). A chave começa com "AQ." (formato novo) ou "AIza" (antigo); copie
                pelo botão ao lado dela.
              </li>
              <li>
                <b class="text-tinta">Por que o nível pago:</b> no nível gratuito, o Google pode usar o que é enviado (conversas,
                áudios e telas de clientes) para melhorar os produtos dele, e pessoas podem revisar esse conteúdo. No nível
                pago, não.
              </li>
              <li>
                <b class="text-tinta">Segurança:</b> a chave é testada com o Google antes de ser guardada, fica cifrada no
                servidor, só é usada nas suas chamadas e nunca aparece de novo: aqui você vê só os 4 últimos caracteres.
              </li>
            </ul>
          </section>
        </div>
      </EstadoDaTela>
    </div>
  </div>
</template>
