<script setup>
import { computed, ref } from 'vue'
import { atendimentoApi } from '@/modulos/atendimento/api'
import { copiaDiretaDisponivel, copiarPelaSelecao, copiarTextoDireto } from '@/modulos/atendimento/areaDeTransferencia'
import { tamanho, TIPOS_DE_SAIDA } from '@/modulos/atendimento/formato'
import { mensagemDe } from '@/utils/erros'
import { salvarResposta } from '@/utils/download'

/**
 * O texto pelo app do Gemini (gemini.google.com), sem usar a cota da API: o sistema monta o pedido e os arquivos
 * (as imagens e os documentos num PDF, os áudios juntos); a pessoa cola o pedido no app, anexa os arquivos e cola a
 * resposta aqui. A resposta passa pela mesma conferência da API e vira uma versão do texto.
 */
const props = defineProps({
  atendimento: { type: Object, required: true },
  /** O tipo que a tela está mostrando (o pedido começa por ele). */
  tipoAtual: { type: String, default: null },
})
const emit = defineEmits(['progresso', 'usado'])

const aberto = ref(false)
const tipo = ref(props.tipoAtual ?? 'resumo')
const pedido = ref(null)
const resposta = ref('')
const acao = ref('')
const mensagem = ref(null)

const tipos = computed(() => TIPOS_DE_SAIDA.filter((t) => t.disponivel))
const faltaBaixar = computed(() => (props.atendimento.anexos ?? []).filter((a) => a.situacao === 'aguardando' && !a.vencido).length)
const respostaLonga = computed(() => resposta.value.length > 400_000)
const textoDosArquivos = computed(() => {
  const n = pedido.value?.arquivos.length ?? 0
  if (!n) return ': este atendimento não tem arquivos para anexar.'
  return ` ${n === 1 ? 'este arquivo' : `estes ${n} arquivos`} (o app aceita até 10)`
})

function abrir() {
  aberto.value = !aberto.value
  if (aberto.value && props.tipoAtual) tipo.value = props.tipoAtual
}

async function montar() {
  acao.value = 'montar'
  mensagem.value = null
  pedido.value = null
  try {
    pedido.value = await atendimentoApi.pedidoParaOApp(props.atendimento.id, tipo.value)
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
  }
}

async function copiar() {
  mensagem.value = null
  const texto = pedido.value?.pedido ?? ''
  try {
    if (copiaDiretaDisponivel()) await copiarTextoDireto(Promise.resolve(texto))
    else if (!copiarPelaSelecao(texto, 'texto')) throw new Error('cópia recusada')
    mensagem.value = { tipo: 'ok', texto: 'Pedido copiado. Cole no Gemini e anexe os arquivos antes de enviar.' }
  } catch {
    mensagem.value = { tipo: 'erro', texto: 'O navegador não deixou copiar. Selecione o texto do pedido abaixo e copie com Ctrl+C.' }
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

async function baixarTodos() {
  acao.value = 'zip'
  mensagem.value = null
  try {
    salvarResposta(await atendimentoApi.arquivosParaOAppZip(props.atendimento.id), 'arquivos-para-o-gemini.zip')
  } catch (e) {
    mensagem.value = { tipo: 'erro', texto: mensagemDe(e) }
  } finally {
    acao.value = ''
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
    pedido.value = null
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

    <div v-if="aberto" class="mt-3 grid gap-4">
      <p class="text-tinta-suave">
        Quando a cota da sua chave acaba, o app do Gemini (gemini.google.com) continua funcionando. O sistema monta o pedido
        e os arquivos; você cola o pedido no app, anexa os arquivos e cola a resposta aqui. O texto passa pela mesma
        conferência da API e fica igual aos outros (editar, copiar, enviar para a base).
      </p>
      <p v-if="faltaBaixar" class="text-carimbo">
        {{ faltaBaixar === 1 ? '1 anexo ainda não foi baixado' : `${faltaBaixar} anexos ainda não foram baixados` }}: clique em
        Processar (os downloads não usam a cota do Gemini) e monte o pedido depois, senão o texto sai sem eles.
      </p>

      <fieldset class="flex flex-wrap items-center gap-x-4 gap-y-2">
        <legend class="rotulo mb-1">Texto</legend>
        <label v-for="t in tipos" :key="t.codigo" class="flex items-center gap-1.5">
          <input v-model="tipo" type="radio" name="tipo-pelo-app" :value="t.codigo" :disabled="!!acao" @change="pedido = null" />
          {{ t.rotulo }}
        </label>
        <button type="button" class="botao-primario" :disabled="!!acao" @click="montar">
          {{ acao === 'montar' ? 'Montando…' : pedido ? 'Montar de novo' : 'Montar o pedido' }}
        </button>
      </fieldset>

      <div v-if="pedido" class="grid gap-4">
        <div v-if="pedido.avisos.length" role="note" class="rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2">
          <p class="font-semibold text-carimbo">Confira antes de mandar</p>
          <ul class="mt-1 list-disc pl-5">
            <li v-for="(aviso, i) in pedido.avisos" :key="i">{{ aviso }}</li>
          </ul>
        </div>

        <ol class="grid gap-4 pl-0">
          <li class="grid gap-2">
            <p><span class="font-semibold">1. Copie o pedido</span> ({{ pedido.pedido.length.toLocaleString('pt-BR') }} caracteres)</p>
            <div class="flex flex-wrap gap-2">
              <button type="button" class="botao-primario" :disabled="!!acao" @click="copiar">Copiar o pedido</button>
              <a class="botao-secundario" href="https://gemini.google.com/app" target="_blank" rel="noopener noreferrer">Abrir o Gemini</a>
            </div>
            <details>
              <summary class="cursor-pointer text-xs text-tinta-suave">Ver o pedido</summary>
              <textarea class="campo mt-2 h-40 w-full font-mono text-xs" readonly :value="pedido.pedido" aria-label="O pedido para o Gemini" />
            </details>
          </li>

          <li class="grid gap-2">
            <p><span class="font-semibold">2. Anexe no Gemini</span>{{ textoDosArquivos }}</p>
            <ul v-if="pedido.arquivos.length" class="grid gap-1.5">
              <li v-for="a in pedido.arquivos" :key="a.nome" class="grid gap-0.5 rounded-[2px] border border-linha px-2 py-1.5">
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
            <div v-if="pedido.arquivos.length > 1">
              <button type="button" class="botao-secundario" :disabled="!!acao" @click="baixarTodos">
                {{ acao === 'zip' ? 'Preparando…' : 'Baixar todos (.zip)' }}
              </button>
            </div>
          </li>

          <li class="grid gap-2">
            <label for="resposta-do-app" class="font-semibold">3. Cole aqui a resposta do Gemini (o bloco de código inteiro)</label>
            <textarea
              id="resposta-do-app"
              v-model="resposta"
              class="campo h-40 w-full font-mono text-xs"
              placeholder='```json&#10;{ "assunto": "...", ... }&#10;```'
            />
            <p v-if="respostaLonga" class="text-xs text-carimbo">A resposta passa de 400 mil caracteres: confira se colou só a resposta.</p>
            <div>
              <button type="button" class="botao-primario" :disabled="!!acao || !resposta.trim() || respostaLonga" @click="usar">
                {{ acao === 'usar' ? 'Conferindo…' : 'Usar a resposta' }}
              </button>
            </div>
          </li>
        </ol>
      </div>

      <p
        v-if="mensagem"
        :role="mensagem.tipo === 'erro' ? 'alert' : 'status'"
        :class="mensagem.tipo === 'erro' ? 'text-carimbo' : 'text-credito'"
      >{{ mensagem.texto }}</p>
    </div>
  </section>
</template>
