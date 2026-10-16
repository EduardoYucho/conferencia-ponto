<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { useNotificacoesStore } from '@/stores/notificacoes'
import SinoNotificacoes from '@/components/SinoNotificacoes.vue'
import AvisosGlobais from '@/components/AvisosGlobais.vue'
import MenuLateral from '@/components/MenuLateral.vue'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'

const auth = useAuthStore()
const ponto = usePontoStore()
const notificacoes = useNotificacoesStore()
const modulos = useAcessoModulosStore()
const route = useRoute()
const router = useRouter()

// Tempo real (SSE) ativo durante toda a sessão, em qualquer tela
watch(
  () => auth.autenticado && !auth.precisaTrocarSenha,
  (ativo) => {
    if (ativo) {
      ponto.conectarTempoReal()
      // relê o usuário (uma sessão salva por uma versão anterior não tem id, titular, pasta...)
      // (falhas daqui não derrubam a tela: sem a lista de pessoas, o seletor "Dados de" oferece tentar de novo)
      auth.atualizarUsuario().catch(() => {})
      auth.carregarTitulares().catch(() => {})
      // o que a pessoa pode usar nos módulos (decide os itens do menu); falha não derruba a tela
      modulos.carregar().catch(() => {})
    } else {
      ponto.desconectarTempoReal()
      modulos.limpar()
    }
  },
  { immediate: true },
)

const mostrarBarra = computed(() => auth.autenticado && !route.meta.publica && !route.meta.semBarra)
/** No celular: a gaveta do menu lateral aberta. */
const menuAberto = ref(false)
const menuLateral = ref(null)
const iniciais = computed(() =>
  (auth.usuario?.nome ?? '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase(),
)

/** Seletor "dados de": admin vê os próprios e os de cada pessoa; a coordenação, os de cada pessoa. */
const opcoesPessoa = computed(() => {
  const outros = auth.titulares.filter((t) => t.login !== auth.usuario?.login)
  return auth.ehTitular
    ? [{ login: auth.usuario.login, nome: 'Meus dados' }, ...outros]
    : outros
})
const pessoaSelecionada = computed({
  get: () => (auth.vendoOsProprios ? auth.usuario?.login : auth.visto),
  // escolher de novo a mesma pessoa não muda nada (zeraria os dados sem recarregar as telas)
  set: (login) => login !== pessoaSelecionada.value && trocarPessoa(login),
})
/**
 * As telas são remontadas ao trocar de pessoa (cada uma carrega os dados da nova pessoa). Ao sair, a tela atual
 * não é remontada (iria buscar dados sem sessão).
 */
const chaveTela = ref(0)
// Qualquer troca da pessoa em tela passa por aqui — inclusive a que o sistema faz sozinho quando a pessoa
// consultada é desativada: os dados da anterior saem da memória antes de as telas serem remontadas.
watch(() => auth.visto, () => {
  if (!auth.autenticado) return
  ponto.trocarPessoa()
  chaveTela.value++
})

function trocarPessoa(login) {
  auth.verComo(login)
}

const recarregandoPessoas = ref(false)
async function recarregarPessoas() {
  recarregandoPessoas.value = true
  try {
    await auth.carregarTitulares()
  } catch {
    // o aviso continua na barra
  } finally {
    recarregandoPessoas.value = false
  }
}

function sair() {
  ponto.limpar()
  notificacoes.limpar()
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div v-if="mostrarBarra" class="flex min-h-screen">
    <MenuLateral ref="menuLateral" v-model:aberto="menuAberto" />
    <div class="min-w-0 flex-1">
      <header class="sticky top-0 z-20 border-b border-linha bg-cartao/80 backdrop-blur-sm">
        <div class="flex h-12 items-center justify-between gap-3 px-4 sm:px-6">
          <div class="flex min-w-0 items-center gap-2 text-sm">
            <button
              type="button"
              class="grid size-9 shrink-0 place-items-center rounded-[3px] border border-linha text-lg leading-none hover:bg-papel-escuro lg:hidden"
              :aria-expanded="menuAberto"
              aria-label="Abrir o menu"
              @click.stop="menuAberto = true"
            >☰</button>
            <p v-if="menuLateral?.ondeEsta" class="min-w-0 truncate text-tinta-suave">
              <span class="hidden sm:inline">{{ menuLateral.ondeEsta.grupo }} › </span><span class="font-semibold text-tinta">{{ menuLateral.ondeEsta.item }}</span>
            </p>
          </div>
          <div class="flex shrink-0 items-center gap-3 text-sm">
            <label v-if="auth.podeVerTodos && opcoesPessoa.length > 1" class="flex items-center gap-2">
              <span class="rotulo hidden whitespace-nowrap xl:inline">Dados de</span>
              <select
                v-model="pessoaSelecionada"
                class="campo max-w-[11rem] py-1! font-sans text-sm font-semibold"
                aria-label="De quem são os dados em tela"
              >
                <option v-for="p in opcoesPessoa" :key="p.login" :value="p.login">{{ p.nome }}</option>
              </select>
            </label>
            <span
              v-if="auth.somenteLeitura"
              class="hidden rounded-[2px] border border-carimbo/40 px-1.5 py-0.5 text-[0.65rem] font-bold tracking-wider text-carimbo uppercase sm:inline"
            >somente leitura</span>
            <RouterLink
              :to="{ name: 'conta' }"
              class="flex items-center gap-3 rounded-[3px] px-1.5 py-1 transition hover:bg-papel-escuro"
              title="Minha conta: senha, pasta dos comprovantes e horário"
            >
              <span class="hidden text-right leading-tight sm:block">
                <span class="block font-semibold">{{ auth.usuario?.nome }}</span>
                <span class="block text-xs text-tinta-suave underline decoration-linha underline-offset-2">Minha conta</span>
              </span>
              <span class="grid size-8 place-items-center rounded-full bg-tinta text-xs font-bold text-cartao" aria-hidden="true">{{ iniciais }}</span>
            </RouterLink>
            <SinoNotificacoes v-if="auth.ehTitular" />
            <button type="button" class="botao-secundario py-1.5! text-xs" @click="sair">Sair</button>
          </div>
        </div>
        <div
          v-if="auth.podeVerTodos && auth.erroTitulares"
          class="border-t border-carimbo/30 bg-carimbo/5 px-4 py-1.5 text-center text-sm text-carimbo"
          role="alert"
        >
          Não foi possível carregar a lista de pessoas.
          <button type="button" class="ml-2 font-semibold underline underline-offset-4" :disabled="recarregandoPessoas" @click="recarregarPessoas">
            {{ recarregandoPessoas ? 'tentando…' : 'tentar de novo' }}
          </button>
        </div>
        <div
          v-if="!auth.vendoOsProprios && auth.ehTitular"
          class="border-t border-carimbo/30 bg-carimbo/5 px-4 py-1.5 text-center text-sm"
          role="status"
        >
          Consultando os dados de <b>{{ auth.pessoaEmTela?.nome }}</b> · somente leitura
          <button type="button" class="ml-2 font-semibold text-carimbo underline underline-offset-4" @click="trocarPessoa(null)">voltar aos meus dados</button>
        </div>
      </header>
      <RouterView :key="chaveTela" />
    </div>
  </div>
  <RouterView v-else :key="chaveTela" />
  <AvisosGlobais />
</template>
