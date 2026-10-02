<script setup>
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { pontoApi } from '@/api/pontoApi'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'

/**
 * Planilha do Google da pessoa em tela: o sistema reescreve as abas (resumo + um mês por aba) a cada mudança no
 * ponto. A pessoa cria a planilha, compartilha com a conta de serviço do sistema e cola o link aqui; depois é
 * só compartilhar a mesma planilha com quem confere.
 */
const props = defineProps({
  podeEditar: { type: Boolean, default: false },
  /** Login de outra pessoa (o administrador alterando a planilha dela); null = a própria. */
  usuario: { type: String, default: null },
})

const auth = useAuthStore()
const ponto = usePontoStore()
const { planilha } = storeToRefs(ponto)

const link = ref('')
const trocando = ref(false)
const ocupado = ref('') // 'conectar' | 'atualizar' | 'desconectar'
const erro = ref('')
const copiado = ref(false)

onMounted(() => ponto.carregarPlanilha())

const vinculada = computed(() => !!planilha.value?.url)
const semIntegracao = computed(() => planilha.value?.situacao === 'SEM_INTEGRACAO')

const situacao = computed(() => {
  const p = planilha.value
  if (!p) return { cor: 'bg-tinta-apagada', texto: 'Carregando…' }
  if (p.situacao === 'SINCRONIZADA') return { cor: 'bg-credito', texto: `Em dia: gravada ${quando(p.sincronizadaEm)}. Cada mudança no ponto chega à planilha em alguns segundos.` }
  if (p.situacao === 'ERRO') return { cor: 'bg-carimbo', texto: p.erro }
  if (p.situacao === 'PENDENTE') return { cor: 'bg-amber-500 animate-pulse', texto: 'Aguardando a primeira gravação…' }
  if (p.url) return { cor: 'bg-amber-500', texto: 'A integração com o Google foi desligada pelo administrador: a planilha não está sendo atualizada.' }
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
    erro.value = e.message
    return false
  } finally {
    ocupado.value = ''
  }
}

async function conectar() {
  if (await executar('conectar', () => pontoApi.vincularPlanilha(link.value.trim(), props.usuario))) {
    link.value = ''
    trocando.value = false
  }
}

const atualizar = () => executar('atualizar', () => pontoApi.sincronizarPlanilha(props.usuario))
const desconectar = () => executar('desconectar', () => pontoApi.desvincularPlanilha(props.usuario))

async function copiarEmail() {
  try {
    await navigator.clipboard.writeText(planilha.value.emailServico)
    copiado.value = true
    setTimeout(() => (copiado.value = false), 2000)
  } catch {
    // sem permissão de área de transferência: o e-mail está na tela para copiar à mão
  }
}
</script>

<template>
  <div>
    <!-- Integração ainda não configurada pelo administrador -->
    <p v-if="semIntegracao && !vinculada" class="text-sm text-tinta-suave">
      A integração com o Google Sheets ainda não foi configurada.
      <RouterLink v-if="auth.ehAdmin" :to="{ name: 'usuarios', hash: '#google' }" class="font-semibold text-tinta underline underline-offset-4">
        Configurar agora
      </RouterLink>
      <template v-else>Peça ao administrador do sistema.</template>
    </p>

    <!-- Planilha vinculada -->
    <template v-if="vinculada">
      <p class="flex items-start gap-2 text-sm" role="status">
        <span class="mt-1.5 size-2 shrink-0 rounded-full" :class="situacao?.cor" aria-hidden="true" />
        <span>
          <a :href="planilha.url" target="_blank" rel="noopener" class="block font-semibold underline decoration-linha underline-offset-4 hover:decoration-tinta">
            {{ planilha.titulo || 'Planilha no Google' }} ↗
          </a>
          <span :class="planilha.situacao === 'ERRO' ? 'text-carimbo' : 'text-tinta-suave'">{{ situacao?.texto }}</span>
        </span>
      </p>
      <div v-if="podeEditar" class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2">
        <button type="button" class="botao-secundario py-1.5! text-xs" :disabled="!!ocupado || semIntegracao" @click="atualizar">
          {{ ocupado === 'atualizar' ? 'Gravando…' : 'Atualizar agora' }}
        </button>
        <button type="button" class="text-xs text-tinta-suave underline underline-offset-4 hover:text-tinta" :disabled="!!ocupado" @click="trocando = !trocando">
          usar outra planilha
        </button>
        <button type="button" class="text-xs text-tinta-suave underline underline-offset-4 hover:text-carimbo" :disabled="!!ocupado" @click="desconectar">
          {{ ocupado === 'desconectar' ? 'desconectando…' : 'desconectar' }}
        </button>
      </div>
      <p class="mt-3 text-sm text-tinta-suave">
        Para quem confere: no Google Sheets, clique em <b>Compartilhar</b> e adicione a pessoa como <b>Leitor</b>
        (ou Comentador). Ela vê os totais sempre atualizados, sem precisar entrar no sistema.
      </p>
    </template>

    <!-- Passo a passo para vincular (ou trocar) -->
    <form
      v-if="podeEditar && planilha?.emailServico && (!vinculada || trocando)"
      class="mt-4"
      novalidate
      @submit.prevent="conectar"
    >
      <ol class="list-decimal space-y-2 pl-5 text-sm">
        <li>
          Crie uma planilha em
          <a href="https://sheets.new" target="_blank" rel="noopener" class="font-semibold underline underline-offset-4">sheets.new</a>
          (ou abra uma que já exista: as abas dela não são apagadas).
        </li>
        <li>
          Clique em <b>Compartilhar</b> e adicione este e-mail como <b>Editor</b>:
          <span class="mt-1 flex flex-wrap items-center gap-2">
            <code class="carimbo break-all rounded-[2px] bg-papel px-2 py-1 text-xs">{{ planilha.emailServico }}</code>
            <button type="button" class="botao-secundario px-2! py-1! text-xs" @click="copiarEmail">{{ copiado ? 'copiado' : 'copiar' }}</button>
          </span>
        </li>
        <li>
          Cole aqui o link da planilha:
          <input
            v-model="link"
            type="url"
            class="campo mt-1.5 text-sm"
            placeholder="https://docs.google.com/spreadsheets/d/…"
            spellcheck="false"
            autocapitalize="off"
            aria-label="Link da planilha do Google"
          />
        </li>
      </ol>
      <button type="submit" class="botao-primario mt-3 py-1.5! text-xs" :disabled="!!ocupado || !link.trim()">
        {{ ocupado === 'conectar' ? 'Gravando a planilha…' : 'Conectar e gravar' }}
      </button>
    </form>

    <p v-if="!podeEditar && !vinculada && !semIntegracao && planilha" class="text-sm text-tinta-suave">
      Nenhuma planilha do Google vinculada.
    </p>

    <p v-if="erro" role="alert" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>
  </div>
</template>
