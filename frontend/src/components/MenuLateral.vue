<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { ACESSOS, INTEGRACOES, LOGS, USUARIOS } from '@/utils/permissoes'

/**
 * O menu lateral: os grupos (Ponto, Atendimentos, Base de conhecimento, Administração), cada um com os submenus.
 * No computador, fica aberto (com os nomes) ou recolhido (só os ícones; o grupo abre os submenus num menu ao lado),
 * e o navegador lembra a escolha e os grupos fechados. No celular, abre por cima da tela pelo botão do alto.
 * Cada item aparece só com a permissão da tela (a soma dos perfis da pessoa com os ajustes só dela): o Ponto para
 * quem tem o próprio ponto ou consulta o da equipe, Atendimentos e Base para quem tem a permissão do módulo, e cada
 * item da Administração com a permissão dele.
 */
const props = defineProps({
  /** No celular: a gaveta aberta. */
  aberto: { type: Boolean, default: false },
})
const emit = defineEmits(['update:aberto'])

const auth = useAuthStore()
const modulos = useAcessoModulosStore()
const route = useRoute()

const CHAVE_RECOLHIDO = 'conferencia-ponto.menu.recolhido'
const CHAVE_FECHADOS = 'conferencia-ponto.menu.fechados'

function ler(chave) {
  try {
    return localStorage.getItem(chave)
  } catch {
    return null
  }
}
function gravar(chave, valor) {
  try {
    localStorage.setItem(chave, valor)
  } catch {
    // sem armazenamento: vale só nesta aba
  }
}
function lerFechados() {
  try {
    const lidos = JSON.parse(ler(CHAVE_FECHADOS) ?? '[]')
    return Array.isArray(lidos) ? lidos : []
  } catch {
    return []
  }
}

const recolhido = ref(ler(CHAVE_RECOLHIDO) === '1')
const fechados = ref(lerFechados())
/** Com o menu recolhido: o grupo cujos submenus estão abertos ao lado. */
const flutuante = ref(null)

const administracao = computed(() =>
  [
    auth.podeAlgum(USUARIOS, ACESSOS, INTEGRACOES) && { nome: 'usuarios', rotulo: 'Usuários' },
    auth.pode(ACESSOS) && { nome: 'perfis', rotulo: 'Perfis de acesso' },
    auth.pode(LOGS) && { nome: 'logs', rotulo: 'Logs' },
  ].filter(Boolean),
)

const grupos = computed(() =>
  [
    (auth.ehTitular || auth.podeVerTodos) && {
      id: 'ponto',
      rotulo: 'Ponto',
      icone: 'relogio',
      itens: [
        { nome: 'painel', rotulo: 'Painel' },
        { nome: 'banco', rotulo: 'Banco de horas' },
        { nome: 'auditoria', rotulo: 'Auditoria' },
        { nome: 'conciliacao', rotulo: 'Conciliação RH' },
        { nome: 'ausencias', rotulo: 'Folgas e feriados' },
        { nome: 'equipe', rotulo: 'Equipe' },
      ],
    },
    modulos.gerador && {
      id: 'atendimentos',
      rotulo: 'Atendimentos',
      icone: 'conversa',
      itens: [
        { nome: 'atendimentos', rotulo: 'Meus atendimentos', tambem: ['atendimento'] },
        { nome: 'atendimento-novo', rotulo: 'Novo atendimento' },
        // com a IA local não há chave: a tela sai do menu
        ...(modulos.iaLocal ? [] : [{ nome: 'chave-gemini', rotulo: 'Minha chave do Gemini' }]),
      ],
    },
    modulos.pesquisar && {
      id: 'base',
      rotulo: 'Base de conhecimento',
      icone: 'livro',
      itens: [{ nome: 'base-conhecimento', rotulo: 'Pesquisar', tambem: ['registro-da-base'] }],
    },
    administracao.value.length > 0 && {
      id: 'administracao',
      rotulo: 'Administração',
      icone: 'engrenagem',
      itens: administracao.value,
    },
  ].filter(Boolean),
)

const ativo = (item) => route.name === item.nome || (item.tambem ?? []).includes(route.name)
const grupoAtivo = (grupo) => grupo.itens.some(ativo)
const grupoAberto = (grupo) => !fechados.value.includes(grupo.id)

/** O grupo, o item e a tela em que a pessoa está (para a barra do alto). */
const ondeEsta = computed(() => {
  for (const grupo of grupos.value) {
    const item = grupo.itens.find(ativo)
    if (item) return { grupo: grupo.rotulo, item: item.rotulo }
  }
  return null
})
defineExpose({ ondeEsta })

function alternarGrupo(grupo) {
  if (recolhido.value && !props.aberto) {
    flutuante.value = flutuante.value === grupo.id ? null : grupo.id
    return
  }
  fechados.value = grupoAberto(grupo) ? [...fechados.value, grupo.id] : fechados.value.filter((g) => g !== grupo.id)
  gravar(CHAVE_FECHADOS, JSON.stringify(fechados.value))
}

function alternarRecolhido() {
  recolhido.value = !recolhido.value
  flutuante.value = null
  gravar(CHAVE_RECOLHIDO, recolhido.value ? '1' : '0')
}

// a tela aberta por um link ou pelo endereço abre o grupo dela; trocar de tela fecha o menu ao lado e a gaveta
watch(
  () => route.name,
  () => {
    flutuante.value = null
    emit('update:aberto', false)
    const grupo = grupos.value.find(grupoAtivo)
    if (grupo && !grupoAberto(grupo)) {
      fechados.value = fechados.value.filter((g) => g !== grupo.id)
      gravar(CHAVE_FECHADOS, JSON.stringify(fechados.value))
    }
  },
)

const raiz = ref(null)
function aoClicarFora(evento) {
  if (flutuante.value && raiz.value && !raiz.value.contains(evento.target)) flutuante.value = null
}
function aoTeclar(evento) {
  if (evento.key !== 'Escape') return
  if (flutuante.value) flutuante.value = null
  else if (props.aberto) emit('update:aberto', false)
}
onMounted(() => {
  document.addEventListener('click', aoClicarFora)
  document.addEventListener('keydown', aoTeclar)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', aoClicarFora)
  document.removeEventListener('keydown', aoTeclar)
})

/** Mostra só os ícones (no computador, recolhido). Na gaveta do celular, sempre com os nomes. */
const soIcones = computed(() => recolhido.value && !props.aberto)

const ICONES = {
  relogio: 'M12 6v6l4 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  conversa: 'M7 9h10M7 13h6m-9 7 3.5-3H19a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h1v3Z',
  livro: 'M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2V5Zm0 16a2 2 0 0 1 2-2h13v2H6M8 7h7',
  engrenagem:
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm7.4-3a7.5 7.5 0 0 0-.1-1.2l2-1.6-2-3.4-2.4 1a7.4 7.4 0 0 0-2-1.2L14.5 3h-4l-.4 2.6a7.4 7.4 0 0 0-2 1.2l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 2.4l-2 1.6 2 3.4 2.4-1a7.4 7.4 0 0 0 2 1.2l.4 2.6h4l.4-2.6a7.4 7.4 0 0 0 2-1.2l2.4 1 2-3.4-2-1.6c.1-.4.1-.8.1-1.2Z',
}
</script>

<template>
  <!-- fundo escuro atrás da gaveta, no celular -->
  <div v-if="aberto" class="fixed inset-0 z-40 bg-tinta/30 lg:hidden" aria-hidden="true" @click="emit('update:aberto', false)" />

  <aside
    ref="raiz"
    class="menu-lateral fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-linha bg-cartao transition-[width,transform] duration-200 lg:sticky lg:top-0 lg:z-30 lg:h-screen lg:translate-x-0"
    :class="[aberto ? 'translate-x-0 shadow-xl' : '-translate-x-full', soIcones ? 'lg:w-16' : 'lg:w-60']"
    aria-label="Menu lateral"
  >
    <div class="flex h-12 shrink-0 items-center gap-2 border-b border-linha px-4" :class="soIcones ? 'lg:justify-center lg:px-0' : ''">
      <svg class="size-6 shrink-0" viewBox="0 0 32 32" aria-hidden="true">
        <circle cx="16" cy="16" r="13" fill="var(--color-cartao)" stroke="var(--color-tinta)" stroke-width="3" />
        <path d="M16 9v7l5 3" stroke="var(--color-carimbo)" stroke-width="3" fill="none" stroke-linecap="round" />
      </svg>
      <span class="truncate text-[0.95rem] font-bold tracking-tight" :class="soIcones ? 'lg:hidden' : ''">Conferência de Ponto</span>
      <button
        type="button"
        class="ml-auto grid size-8 place-items-center rounded-[3px] text-tinta-suave hover:bg-papel-escuro lg:hidden"
        aria-label="Fechar o menu"
        @click="emit('update:aberto', false)"
      >✕</button>
    </div>

    <nav class="min-h-0 flex-1 py-2" :class="soIcones ? 'overflow-visible' : 'overflow-y-auto'" aria-label="Navegação principal">
      <ul class="grid gap-0.5 px-2">
        <li v-for="grupo in grupos" :key="grupo.id" class="relative">
          <button
            type="button"
            class="flex w-full items-center gap-3 rounded-[3px] px-2.5 py-2 text-left text-sm font-semibold transition hover:bg-papel-escuro"
            :class="[grupoAtivo(grupo) ? 'text-tinta' : 'text-tinta-suave', soIcones ? 'lg:justify-center lg:px-0' : '', soIcones && grupoAtivo(grupo) ? 'lg:bg-papel-escuro' : '']"
            :aria-expanded="soIcones ? flutuante === grupo.id : grupoAberto(grupo)"
            :aria-controls="`menu-${grupo.id}`"
            :title="soIcones ? grupo.rotulo : undefined"
            @click.stop="alternarGrupo(grupo)"
          >
            <svg class="size-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path :d="ICONES[grupo.icone]" />
            </svg>
            <span class="min-w-0 flex-1 truncate" :class="soIcones ? 'lg:sr-only' : ''">{{ grupo.rotulo }}</span>
            <span v-if="!soIcones" class="text-xs text-tinta-apagada" aria-hidden="true">{{ grupoAberto(grupo) ? '▾' : '▸' }}</span>
          </button>

          <!-- submenus: abaixo do grupo, ou ao lado quando o menu está recolhido -->
          <ul
            v-if="soIcones ? flutuante === grupo.id : grupoAberto(grupo)"
            :id="`menu-${grupo.id}`"
            :class="soIcones
              ? 'absolute top-0 left-full z-50 ml-2 grid w-56 gap-0.5 rounded-[3px] border border-linha bg-cartao p-2 shadow-lg'
              : 'mt-0.5 mb-1 grid gap-0.5 pl-8'"
          >
            <li v-if="soIcones" class="px-2.5 pt-1 pb-1.5 text-[0.65rem] font-bold tracking-wider text-tinta-apagada uppercase">{{ grupo.rotulo }}</li>
            <li v-for="item in grupo.itens" :key="item.nome">
              <RouterLink
                :to="{ name: item.nome }"
                class="block rounded-[3px] px-2.5 py-1.5 text-sm transition hover:bg-papel-escuro"
                :class="ativo(item) ? 'bg-tinta font-semibold text-cartao hover:bg-tinta' : 'text-tinta-suave hover:text-tinta'"
                :aria-current="ativo(item) ? 'page' : undefined"
              >{{ item.rotulo }}</RouterLink>
            </li>
          </ul>
        </li>
      </ul>
    </nav>

    <div class="hidden shrink-0 border-t border-linha p-2 lg:block">
      <button
        type="button"
        class="flex w-full items-center gap-3 rounded-[3px] px-2.5 py-2 text-sm text-tinta-suave transition hover:bg-papel-escuro hover:text-tinta"
        :class="soIcones ? 'justify-center px-0' : ''"
        :aria-label="soIcones ? 'Abrir o menu' : 'Recolher o menu'"
        :title="soIcones ? 'Abrir o menu' : 'Recolher o menu'"
        @click="alternarRecolhido"
      >
        <span class="text-base leading-none" aria-hidden="true">{{ soIcones ? '»' : '«' }}</span>
        <span v-if="!soIcones">Recolher o menu</span>
      </button>
    </div>
  </aside>
</template>
