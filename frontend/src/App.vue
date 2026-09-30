<script setup>
import { computed, watch } from 'vue'
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
  () => auth.autenticado,
  (logado) => (logado ? ponto.conectarTempoReal() : ponto.desconectarTempoReal()),
  { immediate: true },
)

const mostrarBarra = computed(() => auth.autenticado && !route.meta.publica)
const menu = computed(() => [
  { nome: 'painel', rotulo: 'Painel' },
  { nome: 'auditoria', rotulo: 'Auditoria' },
  { nome: 'conciliacao', rotulo: 'Conciliação RH' },
  { nome: 'ausencias', rotulo: 'Férias e folgas' },
])
const iniciais = computed(() =>
  (auth.usuario?.nome ?? '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase(),
)

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
        <span
          v-if="auth.somenteLeitura"
          class="hidden rounded-[2px] border border-carimbo/40 px-1.5 py-0.5 text-[0.65rem] font-bold tracking-wider text-carimbo uppercase sm:inline"
        >somente leitura</span>
        <span class="hidden text-right leading-tight sm:block">
          <span class="block font-semibold">{{ auth.usuario?.nome }}</span>
          <span class="block text-xs text-tinta-suave">{{ auth.rotuloPerfil }}</span>
        </span>
        <SinoNotificacoes v-if="auth.podeEscrever" />
        <span class="grid size-8 place-items-center rounded-full bg-tinta text-xs font-bold text-cartao" aria-hidden="true">{{ iniciais }}</span>
        <button type="button" class="botao-secundario py-1.5! text-xs" @click="sair">Sair</button>
      </div>
    </div>
  </nav>
  <RouterView />
</template>
