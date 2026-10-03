<script setup>
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { storeToRefs } from 'pinia'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { avisar } from '@/utils/avisar'
import { mensagemDe } from '@/utils/erros'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import Icone from '@/components/Icone.vue'

/**
 * Planilha do Google da pessoa em tela: o sistema reescreve as abas (resumo + um mês por aba) a cada mudança no
 * ponto. A pessoa cria a planilha, compartilha com a conta do Google do sistema e cola o link aqui; depois é
 * só compartilhar a mesma planilha com quem confere.
 */
const props = defineProps({
  podeEditar: { type: Boolean, default: false },
  /** Login de outra pessoa (o administrador alterando a planilha dela); null = a própria. */
  usuario: { type: String, default: null },
})

const auth = useAuthStore()
const ponto = usePontoStore()
const { planilha, erroPlanilha } = storeToRefs(ponto)
const id = useId()

const link = ref('')
const trocando = ref(false)
const ocupado = ref('') // 'conectar' | 'atualizar' | 'desconectar'
const erro = ref('')
const copiado = ref(false)
/** A área de transferência não está disponível (acesso por http na rede local): a pessoa copia à mão. */
const naoCopiou = ref(false)
const carregando = ref(false)
const confirmandoDesvincular = ref(false)
let timerCopiado = null

/** A situação da planilha vem da store (o tempo real a mantém em dia); se a leitura falhar, fica em `erroPlanilha`. */
async function carregar() {
  carregando.value = true
  try {
    await ponto.carregarPlanilha()
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)
onBeforeUnmount(() => clearTimeout(timerCopiado))

const vinculada = computed(() => !!planilha.value?.url)
const semIntegracao = computed(() => planilha.value?.situacao === 'SEM_INTEGRACAO')

/** O servidor diz como a planilha está; aqui só se escolhe a frase e a cor. */
const situacao = computed(() => {
  const p = planilha.value
  if (!p) return null
  if (p.situacao === 'SINCRONIZADA') {
    return { selo: 'selo-positivo', rotulo: 'Em dia', texto: `Gravada ${quando(p.sincronizadaEm)}. Cada mudança no ponto chega à planilha em alguns segundos.` }
  }
  if (p.situacao === 'ERRO') return { selo: 'selo-negativo', rotulo: 'Com erro', texto: p.erro, erro: true }
  if (p.situacao === 'PENDENTE') return { selo: 'selo-atencao', rotulo: 'Aguardando', texto: 'Aguardando a primeira gravação…', pisca: true }
  if (p.url) {
    return { selo: 'selo-atencao', rotulo: 'Parada', texto: 'O administrador desligou a conta do Google do sistema: a planilha não está sendo atualizada.' }
  }
  return null
})

function quando(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const hoje = new Date()
  const hora = d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  return d.toDateString() === hoje.toDateString()
    ? `hoje às ${hora}`
    : `em ${d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} às ${hora}`
}

async function executar(acao, chamada) {
  ocupado.value = acao
  erro.value = ''
  try {
    planilha.value = await chamada()
    return true
  } catch (e) {
    erro.value = mensagemDe(e)
    return false
  } finally {
    ocupado.value = ''
  }
}

/** Depois de gravar: se o Google recusou, o motivo aparece na situação da planilha (não vira "deu certo"). */
const gravouSemErro = () => planilha.value?.situacao !== 'ERRO'

async function conectar() {
  if (await executar('conectar', () => pontoApi.vincularPlanilha(link.value.trim(), props.usuario))) {
    link.value = ''
    trocando.value = false
    if (gravouSemErro()) avisar('Pronto: o sistema já gravou nesta planilha e vai mantê-la atualizada.')
  }
}

async function atualizar() {
  if (await executar('atualizar', () => pontoApi.sincronizarPlanilha(props.usuario)) && gravouSemErro()) {
    avisar('Planilha atualizada.')
  }
}

async function desconectar() {
  if (await executar('desconectar', () => pontoApi.desvincularPlanilha(props.usuario))) {
    confirmandoDesvincular.value = false
    trocando.value = false
    avisar('O sistema parou de gravar nesta planilha.', 'info')
  }
}

async function copiarEmail() {
  naoCopiou.value = false
  try {
    await navigator.clipboard.writeText(planilha.value.emailServico)
    copiado.value = true
    clearTimeout(timerCopiado)
    timerCopiado = setTimeout(() => (copiado.value = false), 2000)
  } catch {
    naoCopiou.value = true
  }
}
</script>

<template>
  <div>
    <!-- Ainda carregando, ou a situação da planilha não pôde ser lida -->
    <EstadoDaTela
      v-if="!planilha"
      :carregando="carregando || !erroPlanilha"
      :erro="erroPlanilha"
      carregando-texto="Vendo como está a planilha…"
      @tentar="carregar"
    />

    <!-- O administrador ainda não ligou o sistema ao Google -->
    <p v-if="semIntegracao && !vinculada" class="rounded-xl bg-superficie-2 px-4 py-3 text-[0.95rem] text-texto-2">
      O sistema ainda não está ligado ao Google, então não dá para usar uma planilha por enquanto.
      <RouterLink v-if="auth.ehAdmin" :to="{ name: 'usuarios', hash: '#google' }" class="link">Ligar o sistema ao Google</RouterLink>
      <template v-else>Peça ao administrador do sistema.</template>
    </p>

    <!-- Planilha vinculada -->
    <template v-if="vinculada">
      <div class="flex flex-wrap items-start gap-x-3 gap-y-2 rounded-xl bg-superficie-2 px-4 py-3" role="status">
        <span v-if="situacao" class="selo" :class="situacao.selo">
          <span class="size-2 rounded-full bg-current" :class="{ 'animate-pulse': situacao.pisca }" aria-hidden="true" />
          {{ situacao.rotulo }}
        </span>
        <p class="min-w-0 flex-1 basis-64 text-[0.95rem]">
          <span class="block font-bold break-words text-texto">{{ planilha.titulo || 'Planilha no Google' }}</span>
          <span :class="situacao?.erro ? 'text-negativo' : 'text-texto-2'">{{ situacao?.texto }}</span>
        </p>
      </div>

      <div class="mt-3 flex flex-wrap items-center gap-2">
        <a :href="planilha.url" target="_blank" rel="noopener" class="botao-primario min-h-11">
          Abrir a planilha <Icone nome="externo" tamanho="18" />
        </a>
        <template v-if="podeEditar">
          <button type="button" class="botao-secundario min-h-11" :disabled="!!ocupado || semIntegracao" @click="atualizar">
            <Icone nome="atualizar" tamanho="18" /> {{ ocupado === 'atualizar' ? 'Gravando…' : 'Atualizar agora' }}
          </button>
          <button v-if="planilha.emailServico" type="button" class="botao-linha" :disabled="!!ocupado" :aria-expanded="trocando" @click="trocando = !trocando">
            Usar outra planilha
          </button>
          <button v-if="!confirmandoDesvincular" type="button" class="botao-linha" :disabled="!!ocupado" @click="confirmandoDesvincular = true">
            Parar de usar esta planilha
          </button>
        </template>
      </div>

      <!-- confirmação antes de desvincular a planilha -->
      <div v-if="confirmandoDesvincular" class="mt-3 rounded-xl border border-negativo-borda bg-negativo-suave p-4" role="group" aria-label="Confirmar: parar de usar esta planilha">
        <p class="font-bold text-negativo">Parar de usar esta planilha?</p>
        <p class="mt-1 text-[0.95rem] text-texto-2">
          O sistema deixa de gravar nela. A planilha continua no Google, com o que já foi gravado, e pode voltar a
          ser usada depois.
        </p>
        <div class="mt-3 flex flex-wrap gap-2">
          <button type="button" class="botao-perigo min-h-11" :disabled="!!ocupado" @click="desconectar">
            {{ ocupado === 'desconectar' ? 'Parando…' : 'Sim, parar de usar' }}
          </button>
          <button type="button" class="botao-secundario min-h-11" :disabled="!!ocupado" @click="confirmandoDesvincular = false">Cancelar</button>
        </div>
      </div>

      <p v-if="semIntegracao" class="mt-3 text-[0.95rem] text-texto-2">
        Para a planilha voltar a ser atualizada, o sistema precisa ser ligado ao Google de novo.
        <RouterLink v-if="auth.ehAdmin" :to="{ name: 'usuarios', hash: '#google' }" class="link">Ligar o sistema ao Google</RouterLink>
        <template v-else>Peça ao administrador do sistema.</template>
      </p>
      <p class="mt-3 text-[0.95rem] text-texto-2">
        Para mostrar a quem confere: na planilha, clique em <b>Compartilhar</b> e adicione a pessoa como <b>Leitor</b>.
        Ela vê os totais sempre atualizados, sem precisar entrar no sistema.
      </p>
    </template>

    <!-- Passo a passo para usar uma planilha (ou trocar) -->
    <form
      v-if="podeEditar && planilha?.emailServico && (!vinculada || trocando)"
      class="mt-4 flex flex-col gap-3"
      novalidate
      @submit.prevent="conectar"
    >
      <p v-if="vinculada" class="font-bold">Para usar outra planilha:</p>
      <ol class="flex flex-col gap-3">
        <li class="flex gap-3">
          <span class="grid size-7 shrink-0 place-items-center rounded-full bg-primaria-suave text-sm font-bold text-primaria" aria-hidden="true">1</span>
          <p class="min-w-0 flex-1 pt-0.5 text-[0.95rem]">
            Crie uma planilha em
            <a href="https://sheets.new" target="_blank" rel="noopener" class="link">sheets.new</a>
            (ou abra uma que já exista: as abas dela não são apagadas).
          </p>
        </li>
        <li class="flex gap-3">
          <span class="grid size-7 shrink-0 place-items-center rounded-full bg-primaria-suave text-sm font-bold text-primaria" aria-hidden="true">2</span>
          <div class="min-w-0 flex-1 pt-0.5 text-[0.95rem]">
            <p>Na planilha, clique em <b>Compartilhar</b> e adicione este e-mail como <b>Editor</b>:</p>
            <p class="mt-1.5 flex flex-wrap items-center gap-2">
              <span class="rounded-lg bg-neutro px-2.5 py-1.5 text-[0.95rem] font-semibold break-all text-texto">{{ planilha.emailServico }}</span>
              <button type="button" class="botao-linha" @click="copiarEmail">{{ copiado ? 'Copiado' : 'Copiar o e-mail' }}</button>
            </p>
            <p v-if="naoCopiou" role="alert" class="mt-1 text-sm text-negativo">Não foi possível copiar: selecione o e-mail e copie.</p>
          </div>
        </li>
        <li class="flex gap-3">
          <span class="grid size-7 shrink-0 place-items-center rounded-full bg-primaria-suave text-sm font-bold text-primaria" aria-hidden="true">3</span>
          <div class="min-w-0 flex-1 pt-0.5">
            <label class="text-[0.95rem]" :for="`${id}-link`">Cole aqui o link da planilha:</label>
            <input
              :id="`${id}-link`"
              v-model="link"
              type="url"
              class="campo mt-1.5"
              placeholder="https://docs.google.com/spreadsheets/d/…"
              spellcheck="false"
              autocapitalize="off"
              autocomplete="off"
            />
          </div>
        </li>
      </ol>
      <div class="flex flex-wrap gap-2 sm:pl-10">
        <button type="submit" class="botao-primario min-h-11" :disabled="!!ocupado || !link.trim()">
          {{ ocupado === 'conectar' ? 'Gravando a planilha…' : 'Usar esta planilha' }}
        </button>
        <button v-if="vinculada" type="button" class="botao-secundario min-h-11" :disabled="!!ocupado" @click="trocando = false">Cancelar</button>
      </div>
    </form>

    <p v-if="!podeEditar && !vinculada && !semIntegracao && planilha" class="rounded-xl bg-superficie-2 px-4 py-3 text-[0.95rem] text-texto-2">
      Nenhuma planilha do Google em uso.
    </p>

    <p v-if="erro" role="alert" class="aviso-erro mt-3">{{ erro }}</p>
  </div>
</template>
