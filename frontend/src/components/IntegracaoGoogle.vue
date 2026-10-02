<script setup>
import { onMounted, ref } from 'vue'
import { pontoApi } from '@/api/pontoApi'

/**
 * Conta de serviço do Google com que o sistema grava as planilhas (administrador). A chave é um arquivo .json
 * criado no Google Cloud: é enviada uma vez e fica só no computador do servidor.
 */
const estado = ref(null)
const erro = ref('')
const aviso = ref('')
const ocupado = ref('') // 'enviar' | 'remover'
const copiado = ref(false)
const confirmandoRemocao = ref(false)
const campoArquivo = ref(null)

async function carregar() {
  try {
    estado.value = await pontoApi.integracaoGoogle()
  } catch (e) {
    erro.value = e.message
  }
}
onMounted(carregar)

async function enviar(evento) {
  const arquivo = evento.target.files?.[0]
  evento.target.value = '' // permite escolher o mesmo arquivo de novo
  if (!arquivo) return
  erro.value = ''
  aviso.value = ''
  if (arquivo.size > 20_000) {
    erro.value = 'Este arquivo é grande demais para ser a chave. Envie o .json baixado em "Chaves" da conta de serviço.'
    return
  }
  ocupado.value = 'enviar'
  try {
    estado.value = await pontoApi.configurarGoogle(await arquivo.text())
    aviso.value = 'Chave aceita pelo Google. Agora cada pessoa vincula a planilha dela em "Minha conta".'
  } catch (e) {
    erro.value = e.message
  } finally {
    ocupado.value = ''
  }
}

async function remover() {
  ocupado.value = 'remover'
  erro.value = ''
  aviso.value = ''
  try {
    estado.value = await pontoApi.removerGoogle()
    confirmandoRemocao.value = false
  } catch (e) {
    erro.value = e.message
  } finally {
    ocupado.value = ''
  }
}

async function copiarEmail() {
  try {
    await navigator.clipboard.writeText(estado.value.email)
    copiado.value = true
    setTimeout(() => (copiado.value = false), 2000)
  } catch {
    // sem permissão de área de transferência: o e-mail está na tela
  }
}
</script>

<template>
  <section id="google" class="cartao mt-6 scroll-mt-6 px-5 py-5" aria-labelledby="titulo-google">
    <h2 id="titulo-google" class="font-sans text-lg font-bold">Planilhas no Google Sheets</h2>
    <p class="mt-1 text-sm text-tinta-suave">
      O sistema mantém a conferência de cada pessoa numa planilha do Google, atualizada a cada mudança no ponto,
      para compartilhar com quem confere. Ele entra no Google com uma conta própria (conta de serviço), criada uma
      vez por você.
    </p>

    <p v-if="!estado && !erro" class="mt-4 text-sm text-tinta-suave">Carregando…</p>

    <!-- Configurada -->
    <template v-if="estado?.configurada">
      <p class="mt-4 flex items-start gap-2 text-sm" role="status">
        <span class="mt-1.5 size-2 shrink-0 rounded-full bg-credito" aria-hidden="true" />
        <span>
          <span class="block font-semibold">Integração ativa</span>
          <span class="text-tinta-suave">
            {{ estado.planilhas }} planilha(s) vinculada(s) · projeto <span class="carimbo">{{ estado.projeto || '—' }}</span>
          </span>
        </span>
      </p>
      <p class="mt-3 text-sm">Cada pessoa compartilha a planilha dela, como Editor, com:</p>
      <p class="mt-1 flex flex-wrap items-center gap-2">
        <code class="carimbo break-all rounded-[2px] bg-papel px-2 py-1 text-xs">{{ estado.email }}</code>
        <button type="button" class="botao-secundario px-2! py-1! text-xs" @click="copiarEmail">{{ copiado ? 'copiado' : 'copiar' }}</button>
      </p>
      <div class="mt-4 flex flex-wrap items-center gap-x-3 gap-y-2">
        <button type="button" class="botao-secundario py-1.5! text-xs" :disabled="!!ocupado" @click="campoArquivo.click()">
          {{ ocupado === 'enviar' ? 'Conferindo com o Google…' : 'Trocar a chave' }}
        </button>
        <button
          v-if="!confirmandoRemocao"
          type="button"
          class="text-xs text-tinta-suave underline underline-offset-4 hover:text-carimbo"
          :disabled="!!ocupado"
          @click="confirmandoRemocao = true"
        >desligar a integração</button>
        <template v-else>
          <span class="text-xs text-carimbo">As planilhas param de ser atualizadas. Desligar?</span>
          <button type="button" class="botao-secundario py-1! text-xs" :disabled="!!ocupado" @click="remover">
            {{ ocupado === 'remover' ? 'Desligando…' : 'Sim, desligar' }}
          </button>
          <button type="button" class="text-xs underline underline-offset-4" @click="confirmandoRemocao = false">cancelar</button>
        </template>
      </div>
    </template>

    <!-- Ainda não configurada: passo a passo -->
    <template v-else-if="estado">
      <ol class="mt-4 list-decimal space-y-2 pl-5 text-sm">
        <li>
          Entre em
          <a href="https://console.cloud.google.com/projectcreate" target="_blank" rel="noopener" class="font-semibold underline underline-offset-4">console.cloud.google.com</a>
          com uma conta do Google e crie um projeto (ex.: <span class="carimbo">conferencia-ponto</span>). É gratuito.
        </li>
        <li>
          Com o projeto selecionado, abra
          <a href="https://console.cloud.google.com/apis/library/sheets.googleapis.com" target="_blank" rel="noopener" class="font-semibold underline underline-offset-4">Google Sheets API</a>
          e clique em <b>Ativar</b>.
        </li>
        <li>
          Em
          <a href="https://console.cloud.google.com/iam-admin/serviceaccounts" target="_blank" rel="noopener" class="font-semibold underline underline-offset-4">IAM e administrador → Contas de serviço</a>,
          clique em <b>Criar conta de serviço</b>, dê um nome (ex.: <span class="carimbo">planilhas-ponto</span>) e
          conclua. Não precisa conceder papéis nem acesso a usuários.
        </li>
        <li>
          Abra a conta criada, vá à aba <b>Chaves</b> e escolha <b>Adicionar chave → Criar nova chave → JSON</b>.
          O navegador baixa um arquivo <span class="carimbo">.json</span>.
        </li>
        <li>Envie esse arquivo aqui:</li>
      </ol>
      <button type="button" class="botao-primario mt-3 py-1.5! text-xs" :disabled="!!ocupado" @click="campoArquivo.click()">
        {{ ocupado === 'enviar' ? 'Conferindo com o Google…' : 'Enviar a chave (.json)' }}
      </button>
      <p class="mt-3 text-xs text-tinta-suave">
        A chave fica só no computador onde o sistema está instalado (fora do banco de dados) e nunca é mostrada de
        novo. Guarde o arquivo como uma senha: com ele dá para editar as planilhas compartilhadas com a conta.
      </p>
    </template>

    <input ref="campoArquivo" type="file" accept=".json,application/json" class="hidden" aria-label="Arquivo da chave da conta de serviço" @change="enviar" />

    <p v-if="aviso" role="status" class="mt-3 rounded-[3px] border border-credito/40 bg-credito/10 px-3 py-2 text-sm text-credito">{{ aviso }}</p>
    <p v-if="erro" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>
  </section>
</template>
