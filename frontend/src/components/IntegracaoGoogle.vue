<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'

/**
 * Conta do Google com que o sistema grava as planilhas (administrador). No Google ela se chama "conta de
 * serviço"; a chave é um arquivo .json criado no Google Cloud: é enviada uma vez e fica só no computador do
 * servidor. O conteúdo da chave nunca é mostrado, guardado nem registrado por esta tela.
 */
const estado = ref(null)
const carregando = ref(false)
/** Falha ao ler a situação (com "Tentar de novo"); `erro` é a falha de uma ação. */
const erroCarga = ref(null)
const erro = ref('')
const ocupado = ref('') // 'enviar' | 'remover'
const copiado = ref(false)
/** A área de transferência não está disponível (acesso por http na rede local): a pessoa copia à mão. */
const naoCopiou = ref(false)
const confirmandoRemocao = ref(false)
const passosAbertos = ref(false)
const campoArquivo = ref(null)
let timerCopiado = null

async function carregar() {
  carregando.value = true
  erroCarga.value = null
  try {
    estado.value = await pontoApi.integracaoGoogle()
  } catch (e) {
    erroCarga.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)
onBeforeUnmount(() => clearTimeout(timerCopiado))

async function enviar(evento) {
  const arquivo = evento.target.files?.[0]
  evento.target.value = '' // permite escolher o mesmo arquivo de novo
  if (!arquivo) return
  erro.value = ''
  if (arquivo.size > 20_000) {
    erro.value = 'Este arquivo é grande demais para ser a chave. Envie o .json baixado em "Chaves" da conta de serviço.'
    return
  }
  ocupado.value = 'enviar'
  try {
    let chave
    try {
      chave = await arquivo.text()
    } catch {
      erro.value = 'Não foi possível ler este arquivo. Escolha de novo o .json baixado em "Chaves" da conta de serviço.'
      return
    }
    estado.value = await pontoApi.configurarGoogle(chave)
    avisar('Chave aceita pelo Google. Agora cada pessoa escolhe a planilha dela em "Minha conta".')
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    ocupado.value = ''
  }
}

async function remover() {
  ocupado.value = 'remover'
  erro.value = ''
  try {
    estado.value = await pontoApi.removerGoogle()
    confirmandoRemocao.value = false
    avisar('Conta do Google removida: as planilhas não são mais atualizadas.', 'info')
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    ocupado.value = ''
  }
}

async function copiarEmail() {
  naoCopiou.value = false
  try {
    await navigator.clipboard.writeText(estado.value.email)
    copiado.value = true
    clearTimeout(timerCopiado)
    timerCopiado = setTimeout(() => (copiado.value = false), 2000)
  } catch {
    naoCopiou.value = true
  }
}

const planilhas = (n) => (n === 1 ? '1 planilha' : `${n} planilhas`)
</script>

<template>
  <section id="google" class="cartao scroll-mt-20 p-5 sm:p-6" aria-labelledby="titulo-google">
    <h2 id="titulo-google" class="titulo-secao">Conta do Google do sistema</h2>
    <p class="mt-1 text-[0.95rem] text-texto-3">
      É com ela que o sistema grava a planilha de cada pessoa no Google Planilhas. Você cria essa conta uma vez, no
      Google, e envia a chave dela aqui.
    </p>

    <!-- Ainda carregando, ou a situação não pôde ser lida -->
    <div v-if="!estado" class="mt-4">
      <EstadoDaTela :carregando="carregando" :erro="erroCarga" carregando-texto="Vendo se o sistema está ligado ao Google…" @tentar="carregar" />
    </div>

    <!-- Ligada -->
    <template v-if="estado?.configurada">
      <div class="mt-4 flex flex-wrap items-start gap-x-3 gap-y-2 rounded-xl bg-superficie-2 px-4 py-3" role="status">
        <span class="selo selo-positivo"><Icone nome="certo" tamanho="16" /> Ligada</span>
        <p class="min-w-0 flex-1 basis-64 text-[0.95rem] text-texto-2">
          {{ estado.planilhas ? `${planilhas(estado.planilhas)} em uso` : 'Nenhuma planilha em uso ainda' }}
          <template v-if="estado.projeto"> · projeto no Google: <span class="font-semibold break-all text-texto">{{ estado.projeto }}</span></template>
        </p>
      </div>

      <p class="mt-4 text-[0.95rem]">Cada pessoa compartilha a planilha dela, como <b>Editor</b>, com este e-mail:</p>
      <p class="mt-1.5 flex flex-wrap items-center gap-2">
        <span class="rounded-lg bg-neutro px-2.5 py-1.5 text-[0.95rem] font-semibold break-all text-texto">{{ estado.email }}</span>
        <button type="button" class="botao-linha" @click="copiarEmail">{{ copiado ? 'Copiado' : 'Copiar o e-mail' }}</button>
      </p>
      <p v-if="naoCopiou" role="alert" class="mt-1 text-sm text-negativo">Não foi possível copiar: selecione o e-mail e copie.</p>

      <div class="mt-4 flex flex-wrap items-center gap-2">
        <button type="button" class="botao-secundario min-h-11" :disabled="!!ocupado" @click="campoArquivo.click()">
          <Icone nome="enviar" tamanho="18" /> {{ ocupado === 'enviar' ? 'Conferindo com o Google…' : 'Trocar a chave' }}
        </button>
        <button v-if="!confirmandoRemocao" type="button" class="botao-linha" :disabled="!!ocupado" @click="confirmandoRemocao = true">
          Remover a conta do Google
        </button>
      </div>

      <!-- confirmação antes de remover -->
      <div v-if="confirmandoRemocao" class="mt-3 rounded-xl border border-negativo-borda bg-negativo-suave p-4" role="group" aria-label="Confirmar: remover a conta do Google">
        <p class="font-bold text-negativo">Remover a conta do Google do sistema?</p>
        <p class="mt-1 text-[0.95rem] text-texto-2">
          As planilhas de todas as pessoas param de ser atualizadas (elas continuam no Google, com o que já foi
          gravado). Para voltar a atualizar, será preciso enviar a chave de novo.
        </p>
        <div class="mt-3 flex flex-wrap gap-2">
          <button type="button" class="botao-perigo min-h-11" :disabled="!!ocupado" @click="remover">
            {{ ocupado === 'remover' ? 'Removendo…' : 'Sim, remover a conta' }}
          </button>
          <button type="button" class="botao-secundario min-h-11" :disabled="!!ocupado" @click="confirmandoRemocao = false">Cancelar</button>
        </div>
      </div>
    </template>

    <!-- Ainda não ligada: enviar a chave (com o passo a passo para criá-la) -->
    <template v-else-if="estado">
      <div class="mt-4 flex flex-wrap items-start gap-x-3 gap-y-2 rounded-xl bg-superficie-2 px-4 py-3" role="status">
        <span class="selo selo-neutro">Não ligada</span>
        <p class="min-w-0 flex-1 basis-64 text-[0.95rem] text-texto-2">
          O sistema ainda não grava planilhas no Google.
          <template v-if="estado.planilhas">
            {{ estado.planilhas === 1 ? 'Há 1 planilha parada' : `Há ${estado.planilhas} planilhas paradas` }}, esperando a chave.
          </template>
        </p>
      </div>

      <div class="mt-4 flex flex-wrap items-center gap-2">
        <button type="button" class="botao-primario min-h-11" :disabled="!!ocupado" @click="campoArquivo.click()">
          <Icone nome="enviar" tamanho="18" /> {{ ocupado === 'enviar' ? 'Conferindo com o Google…' : 'Enviar a chave (arquivo .json)' }}
        </button>
        <button type="button" class="botao-linha" :aria-expanded="passosAbertos" aria-controls="passos-google" @click="passosAbertos = !passosAbertos">
          {{ passosAbertos ? 'Esconder o passo a passo' : 'Ainda não tenho a chave: ver o passo a passo' }}
          <Icone nome="abaixo" tamanho="18" class="transition-transform" :class="{ 'rotate-180': passosAbertos }" />
        </button>
      </div>

      <ol v-if="passosAbertos" id="passos-google" class="mt-4 list-decimal space-y-2.5 rounded-xl bg-superficie-2 py-4 pr-4 pl-9 text-[0.95rem] text-texto-2">
        <li>
          Entre em
          <a href="https://console.cloud.google.com/projectcreate" target="_blank" rel="noopener" class="link">console.cloud.google.com</a>
          com uma conta do Google e crie um projeto (por exemplo, <b>conferencia-ponto</b>). É gratuito.
        </li>
        <li>
          Com o projeto escolhido, abra
          <a href="https://console.cloud.google.com/apis/library/sheets.googleapis.com" target="_blank" rel="noopener" class="link">Google Sheets API</a>
          e clique em <b>Ativar</b>.
        </li>
        <li>
          Em
          <a href="https://console.cloud.google.com/iam-admin/serviceaccounts" target="_blank" rel="noopener" class="link">IAM e administrador → Contas de serviço</a>,
          clique em <b>Criar conta de serviço</b>, dê um nome (por exemplo, <b>planilhas-ponto</b>) e conclua.
          Não precisa conceder papéis nem acesso a usuários.
        </li>
        <li>
          Abra a conta criada, vá à aba <b>Chaves</b> e escolha <b>Adicionar chave → Criar nova chave → JSON</b>.
          O navegador baixa um arquivo <b>.json</b>.
        </li>
        <li>Envie esse arquivo no botão <b>Enviar a chave</b>, aqui em cima.</li>
      </ol>

      <p class="mt-3 text-sm text-texto-3">
        A chave fica só no computador onde o sistema está instalado e nunca é mostrada de novo. Guarde o arquivo
        como uma senha: com ele dá para alterar as planilhas compartilhadas com a conta.
      </p>
    </template>

    <input ref="campoArquivo" type="file" accept=".json,application/json" class="hidden" aria-label="Arquivo da chave da conta do Google (.json)" @change="enviar" />

    <p v-if="erro" role="alert" class="aviso-erro mt-3">{{ erro }}</p>
  </section>
</template>
