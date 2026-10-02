<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { useNotificacoesStore } from '@/stores/notificacoes'
import SinoNotificacoes from '@/components/SinoNotificacoes.vue'

const auth = useAuthStore()
const ponto = usePontoStore()
const notificacoes = useNotificacoesStore()
const route = useRoute()
const router = useRouter()

// Tempo real (SSE) ativo durante toda a sessão, em qualquer tela
watch(
  () => auth.autenticado && !auth.precisaTrocarSenha,
  (ativo) => {
    if (ativo) {
      ponto.conectarTempoReal()
      // relê o usuário (uma sessão salva por uma versão anterior não tem id, titular, pasta...)
      auth.atualizarUsuario().catch(() => {})
      auth.carregarTitulares().catch(() => {})
    } else {
      ponto.desconectarTempoReal()
    }
  },
  { immediate: true },
)

const mostrarBarra = computed(() => auth.autenticado && !route.meta.publica && !route.meta.semBarra)
const menu = computed(() => [
  { nome: 'painel', rotulo: 'Painel' },
  { nome: 'auditoria', rotulo: 'Auditoria' },
  { nome: 'conciliacao', rotulo: 'Conciliação RH' },
  { nome: 'ausencias', rotulo: 'Folgas e feriados' },
  ...(auth.ehAdmin ? [{ nome: 'usuarios', rotulo: 'Usuários' }] : []),
])
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
watch(() => auth.visto, () => {
  if (auth.autenticado) chaveTela.value++
})

function trocarPessoa(login) {
  auth.verComo(login)
  ponto.trocarPessoa()
}

function sair() {
  ponto.limpar()
  notificacoes.limpar()
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <nav v-if="mostrarBarra" class="relative z-40 border-b border-linha bg-cartao/80 backdrop-blur-sm" aria-label="Navegação principal">
    <div class="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-2 sm:px-6">
      <div class="-mx-1 flex items-center gap-1 overflow-x-auto px-1 text-sm">
        <RouterLink
          v-for="item in menu"
          :key="item.nome"
          :to="{ name: item.nome }"
          class="rounded-[3px] px-3 py-1.5 font-semibold whitespace-nowrap text-tinta-suave transition hover:bg-papel-escuro hover:text-tinta"
          active-class="bg-tinta! text-cartao! hover:bg-tinta!"
        >{{ item.rotulo }}</RouterLink>
      </div>
      <div class="flex items-center gap-3 text-sm">
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
      v-if="!auth.vendoOsProprios && auth.ehTitular"
      class="border-t border-carimbo/30 bg-carimbo/5 px-4 py-1.5 text-center text-sm"
      role="status"
    >
      Consultando os dados de <b>{{ auth.pessoaEmTela?.nome }}</b> · somente leitura
      <button type="button" class="ml-2 font-semibold text-carimbo underline underline-offset-4" @click="trocarPessoa(null)">voltar aos meus dados</button>
    </div>
  </nav>
  <RouterView :key="chaveTela" />
</template>
