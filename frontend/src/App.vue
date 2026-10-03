<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { TEMAS, definirTema, tema } from '@/utils/tema'
import SinoNotificacoes from '@/components/SinoNotificacoes.vue'
import AvisosGlobais from '@/components/AvisosGlobais.vue'
import Icone from '@/components/Icone.vue'

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
      ponto.carregarBase()
      // relê o usuário (uma sessão salva por uma versão anterior não tem id, titular, pasta...)
      // (falhas daqui não derrubam a tela: sem a lista de pessoas, o seletor "Dados de" oferece tentar de novo)
      auth.atualizarUsuario().catch(() => {})
      auth.carregarTitulares().catch(() => {})
    } else {
      ponto.desconectarTempoReal()
    }
  },
  { immediate: true },
)

const mostrarMenu = computed(() => auth.autenticado && !route.meta.publica && !route.meta.semBarra)
/** Os dados em tela são de quem está usando (senão, os nomes do menu não dizem "meu"). */
const meus = computed(() => auth.vendoOsProprios && auth.ehTitular)

const itens = computed(() => [
  { nome: 'painel', rotulo: 'Início', curto: 'Início', icone: 'inicio' },
  { nome: 'meu-ponto', rotulo: meus.value ? 'Meu ponto' : 'Ponto dia a dia', curto: 'Ponto', icone: 'calendario' },
  { nome: 'banco', rotulo: 'Banco de horas', curto: 'Banco', icone: 'relogio' },
  { nome: 'conciliacao', rotulo: 'Conferir com o RH', curto: 'RH', icone: 'comparar', aviso: ponto.pendentesRh || 0 },
  { nome: 'ausencias', rotulo: 'Folgas e feriados', curto: 'Folgas', icone: 'sol' },
  { nome: 'equipe', rotulo: 'Equipe', curto: 'Equipe', icone: 'equipe' },
])
const itensAdmin = computed(() => (auth.ehAdmin
  ? [
      { nome: 'usuarios', rotulo: 'Usuários', icone: 'usuario' },
      { nome: 'logs', rotulo: 'Logs do sistema', icone: 'lista' },
    ]
  : []))
/** Barra de baixo do celular: o que se usa todo dia; o resto fica em "Mais". */
const itensCelular = computed(() => ['painel', 'meu-ponto', 'conciliacao', 'equipe']
  .map((nome) => itens.value.find((i) => i.nome === nome)))
const itensMais = computed(() => [
  ...itens.value.filter((i) => !itensCelular.value.includes(i)),
  ...itensAdmin.value,
  { nome: 'conta', rotulo: 'Minha conta', icone: 'engrenagem' },
])
const naAbaMais = computed(() => itensMais.value.some((i) => i.nome === route.name))
const ICONES_TEMA = { claro: 'sol', escuro: 'lua', auto: 'monitor' }

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

// ------------------------------------------------------------- "Mais" (celular)
const maisAberto = ref(false)
watch(() => route.fullPath, () => (maisAberto.value = false))
function aoTeclar(evento) {
  if (evento.key === 'Escape') maisAberto.value = false
}
window.addEventListener('keydown', aoTeclar)
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclar))

function sair() {
  maisAberto.value = false
  ponto.limpar()
  notificacoes.limpar()
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <template v-if="mostrarMenu">
    <a href="#conteudo" class="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-[70] focus:rounded-lg focus:bg-botao focus:px-3 focus:py-2 focus:text-sobre-botao">
      Pular para o conteúdo
    </a>

    <!-- Menu lateral (computador) -->
    <aside class="fixed inset-y-0 left-0 z-40 hidden w-64 flex-col gap-5 overflow-y-auto border-r border-borda bg-superficie px-3.5 py-5 lg:flex">
      <RouterLink :to="auth.rotaInicial()" class="flex items-center gap-2.5 px-2">
        <span class="grid size-9 place-items-center rounded-[10px] bg-botao text-sobre-botao"><Icone nome="relogio" /></span>
        <span class="text-[1.05rem] leading-tight font-extrabold tracking-tight">Conferência de Ponto</span>
      </RouterLink>

      <nav class="flex flex-col gap-1" aria-label="Menu principal">
        <RouterLink
          v-for="item in itens"
          :key="item.nome"
          :to="{ name: item.nome }"
          class="item-menu"
          :class="{ 'item-menu-ativo': route.name === item.nome }"
          :aria-current="route.name === item.nome ? 'page' : undefined"
        >
          <Icone :nome="item.icone" />
          <span class="min-w-0 flex-1">{{ item.rotulo }}</span>
          <span
            v-if="item.aviso"
            class="grid h-[22px] min-w-[22px] place-items-center rounded-full bg-negativo-solido px-1.5 text-xs font-bold text-white"
            :aria-label="`${item.aviso} para conferir`"
          >{{ item.aviso > 99 ? '99+' : item.aviso }}</span>
        </RouterLink>
      </nav>

      <nav v-if="itensAdmin.length" class="flex flex-col gap-1" aria-label="Administração">
        <p class="px-3 pb-1 text-xs font-bold tracking-wider text-texto-3 uppercase">Administração</p>
        <RouterLink
          v-for="item in itensAdmin"
          :key="item.nome"
          :to="{ name: item.nome }"
          class="item-menu"
          :class="{ 'item-menu-ativo': route.name === item.nome }"
          :aria-current="route.name === item.nome ? 'page' : undefined"
        >
          <Icone :nome="item.icone" />
          {{ item.rotulo }}
        </RouterLink>
      </nav>

      <div class="mt-auto flex flex-col gap-3">
        <div>
          <p id="rotulo-tema" class="px-1 pb-1.5 text-xs font-bold tracking-wider text-texto-3 uppercase">Aparência</p>
          <div class="grid grid-cols-3 gap-1 rounded-xl bg-neutro p-1" role="group" aria-labelledby="rotulo-tema">
            <button
              v-for="t in TEMAS"
              :key="t.valor"
              type="button"
              class="flex min-h-9 flex-col items-center justify-center gap-0.5 rounded-lg px-1 py-1.5 text-xs font-semibold transition"
              :class="tema === t.valor ? 'bg-superficie text-texto shadow-sm' : 'text-texto-3 hover:text-texto'"
              :aria-pressed="tema === t.valor"
              :title="t.valor === 'auto' ? 'Acompanha o tema do computador' : undefined"
              @click="definirTema(t.valor)"
            >
              <Icone :nome="ICONES_TEMA[t.valor]" tamanho="17" />
              {{ t.valor === 'auto' ? 'Auto' : t.rotulo }}
            </button>
          </div>
        </div>
        <div class="flex items-stretch gap-2">
          <RouterLink
            :to="{ name: 'conta' }"
            class="flex min-w-0 flex-1 items-center gap-2.5 rounded-xl border border-borda px-2.5 py-2 transition hover:bg-neutro"
            :class="{ 'border-primaria-borda bg-primaria-suave': route.name === 'conta' }"
            title="Senha, pasta dos comprovantes, horário de trabalho e planilha"
          >
            <span class="grid size-9 shrink-0 place-items-center rounded-full bg-inverso text-[0.8rem] font-bold text-sobre-inverso" aria-hidden="true">{{ iniciais }}</span>
            <span class="min-w-0 leading-tight">
              <span class="block truncate text-sm font-bold">{{ auth.usuario?.nome }}</span>
              <span class="block text-[0.8rem] text-texto-3">Minha conta</span>
            </span>
          </RouterLink>
          <button
            type="button"
            class="grid w-11 shrink-0 place-items-center rounded-xl border border-borda text-texto-3 transition hover:bg-neutro hover:text-texto"
            aria-label="Sair do sistema"
            title="Sair"
            @click="sair"
          ><Icone nome="sair" /></button>
        </div>
      </div>
    </aside>

    <div class="flex min-h-dvh flex-col lg:pl-64">
      <!-- Barra de cima: de quem são os dados e os avisos -->
      <header class="sticky top-0 z-30 border-b border-borda bg-superficie/90 backdrop-blur-sm">
        <div class="flex min-h-14 items-center gap-3 px-4 py-2 sm:px-6 lg:px-8">
          <RouterLink :to="auth.rotaInicial()" class="flex items-center gap-2 lg:hidden" aria-label="Conferência de Ponto: início">
            <span class="grid size-8 place-items-center rounded-[9px] bg-botao text-sobre-botao"><Icone nome="relogio" tamanho="18" /></span>
            <span class="hidden text-base font-extrabold tracking-tight sm:inline">Conferência de Ponto</span>
          </RouterLink>
          <p v-if="!auth.vendoOsProprios || !auth.ehTitular" class="hidden min-w-0 items-center gap-2 text-sm text-texto-2 lg:flex" role="status">
            <span class="selo selo-atencao">Somente consulta</span>
            <span v-if="auth.pessoaEmTela" class="truncate">Você está vendo os dados de <b class="text-texto">{{ auth.pessoaEmTela.nome }}</b>.</span>
          </p>
          <div class="ml-auto flex items-center gap-2 sm:gap-3">
            <label v-if="auth.podeVerTodos && opcoesPessoa.length > 1" class="flex items-center gap-2">
              <span class="hidden text-sm font-semibold whitespace-nowrap text-texto-3 sm:inline">Dados de</span>
              <select
                v-model="pessoaSelecionada"
                class="campo max-w-[11rem] py-2! text-sm font-semibold sm:max-w-[14rem]"
                aria-label="De quem são os dados em tela"
              >
                <option v-for="p in opcoesPessoa" :key="p.login" :value="p.login">{{ p.nome }}</option>
              </select>
            </label>
            <SinoNotificacoes v-if="auth.ehTitular" />
          </div>
        </div>
        <div
          v-if="auth.podeVerTodos && auth.erroTitulares"
          class="border-t border-negativo-borda bg-negativo-suave px-4 py-2 text-center text-sm text-negativo"
          role="alert"
        >
          Não foi possível carregar a lista de pessoas.
          <button type="button" class="ml-2 font-bold underline underline-offset-4" :disabled="recarregandoPessoas" @click="recarregarPessoas">
            {{ recarregandoPessoas ? 'Tentando…' : 'Tentar de novo' }}
          </button>
        </div>
        <div
          v-if="!auth.vendoOsProprios && auth.ehTitular"
          class="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-t border-atencao-borda bg-atencao-suave px-4 py-2 text-sm text-atencao"
          role="status"
        >
          <span class="lg:hidden">Vendo os dados de <b>{{ auth.pessoaEmTela?.nome }}</b> (somente consulta).</span>
          <span class="hidden lg:inline">{{ route.name === 'conta' ? 'Você está na conta de outra pessoa: o ponto dela é só consulta.' : 'Enquanto consulta outra pessoa, nada pode ser alterado.' }}</span>
          <button type="button" class="font-bold underline underline-offset-4" @click="trocarPessoa(null)">Voltar aos meus dados</button>
        </div>
      </header>

      <div id="conteudo" class="flex-1">
        <RouterView :key="chaveTela" />
      </div>
    </div>

    <!-- Barra de baixo (celular) -->
    <nav class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-borda bg-superficie pb-[env(safe-area-inset-bottom)] lg:hidden" aria-label="Menu principal">
      <RouterLink
        v-for="item in itensCelular"
        :key="item.nome"
        :to="{ name: item.nome }"
        class="relative flex min-h-14 flex-col items-center justify-center gap-0.5 px-1 text-xs font-semibold"
        :class="route.name === item.nome ? 'text-primaria' : 'text-texto-3'"
        :aria-current="route.name === item.nome ? 'page' : undefined"
        :aria-label="item.rotulo"
      >
        <span class="relative">
          <Icone :nome="item.icone" tamanho="22" />
          <span
            v-if="item.aviso"
            class="absolute -top-1.5 -right-2.5 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-negativo-solido px-1 text-[0.65rem] font-bold text-white"
          >{{ item.aviso > 9 ? '9+' : item.aviso }}</span>
        </span>
        {{ item.curto }}
      </RouterLink>
      <button
        type="button"
        class="flex min-h-14 flex-col items-center justify-center gap-0.5 px-1 text-xs font-semibold"
        :class="naAbaMais || maisAberto ? 'text-primaria' : 'text-texto-3'"
        :aria-expanded="maisAberto"
        aria-controls="menu-mais"
        @click="maisAberto = !maisAberto"
      >
        <Icone nome="mais" tamanho="22" />
        Mais
      </button>
    </nav>

    <Transition enter-active-class="transition duration-200" enter-from-class="opacity-0" leave-active-class="transition duration-150" leave-to-class="opacity-0">
      <div v-if="maisAberto" class="fixed inset-0 z-[45] flex items-end bg-black/45 lg:hidden" @mousedown.self="maisAberto = false">
        <section id="menu-mais" role="dialog" aria-modal="true" aria-label="Mais opções" class="w-full rounded-t-2xl bg-superficie px-4 pt-4 pb-[calc(1rem+env(safe-area-inset-bottom))] shadow-xl">
          <div class="mb-3 flex items-center justify-between">
            <p class="flex items-center gap-2.5">
              <span class="grid size-9 place-items-center rounded-full bg-inverso text-[0.8rem] font-bold text-sobre-inverso" aria-hidden="true">{{ iniciais }}</span>
              <span class="text-base font-bold">{{ auth.usuario?.nome }}</span>
            </p>
            <button type="button" class="grid size-11 place-items-center rounded-xl text-texto-3 hover:bg-neutro" aria-label="Fechar" @click="maisAberto = false"><Icone nome="fechar" /></button>
          </div>
          <nav class="flex flex-col gap-1" aria-label="Mais opções">
            <RouterLink
              v-for="item in itensMais"
              :key="item.nome"
              :to="{ name: item.nome }"
              class="item-menu"
              :class="{ 'item-menu-ativo': route.name === item.nome }"
            >
              <Icone :nome="item.icone" />
              {{ item.rotulo }}
            </RouterLink>
          </nav>
          <p id="rotulo-tema-celular" class="mt-4 px-1 pb-1.5 text-xs font-bold tracking-wider text-texto-3 uppercase">Aparência</p>
          <div class="grid grid-cols-3 gap-1 rounded-xl bg-neutro p-1" role="group" aria-labelledby="rotulo-tema-celular">
            <button
              v-for="t in TEMAS"
              :key="t.valor"
              type="button"
              class="flex min-h-11 items-center justify-center gap-1.5 rounded-lg px-1 text-sm font-semibold transition"
              :class="tema === t.valor ? 'bg-superficie text-texto shadow-sm' : 'text-texto-3'"
              :aria-pressed="tema === t.valor"
              @click="definirTema(t.valor)"
            >
              <Icone :nome="ICONES_TEMA[t.valor]" tamanho="17" />
              {{ t.rotulo }}
            </button>
          </div>
          <button type="button" class="botao-secundario mt-4 min-h-11 w-full" @click="sair"><Icone nome="sair" /> Sair do sistema</button>
        </section>
      </div>
    </Transition>
  </template>

  <RouterView v-else />
  <AvisosGlobais :com-menu="mostrarMenu" />
</template>
