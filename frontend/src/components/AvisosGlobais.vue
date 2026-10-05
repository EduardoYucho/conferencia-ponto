<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { conexao } from '@/api/http'
import { authApi } from '@/api/authApi'
import { avisos } from '@/utils/erros'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'

/**
 * Avisos que valem para qualquer tela, sempre no mesmo lugar (rodapé):
 * - sem conexão com o sistema (some sozinho quando volta);
 * - a alteração foi salva, mas a tela não pôde se atualizar;
 * - o sistema foi atualizado e a aba precisa ser recarregada;
 * - algo deu errado e nenhuma tela tratou.
 */
const auth = useAuthStore()
const ponto = usePontoStore()

// Uma oscilação de 1 ou 2 segundos no tempo real não vira aviso: só a queda que dura.
const tempoRealCaiu = ref(false)
let timer = null
watch(() => ponto.tempoReal, (status) => {
  clearTimeout(timer)
  if (status === 'reconectando') timer = setTimeout(() => (tempoRealCaiu.value = true), 6000)
  else tempoRealCaiu.value = false
}, { immediate: true })

const semConexao = computed(() => auth.autenticado && (conexao.semServidor || tempoRealCaiu.value))

// Enquanto o sistema não responde, pergunta de novo a cada 5 s: o aviso some sozinho quando ele volta (qualquer
// resposta desliga `conexao.semServidor`), sem a pessoa precisar clicar em nada.
let sonda = null
watch(() => auth.autenticado && conexao.semServidor, (caiu) => {
  clearInterval(sonda)
  if (caiu) sonda = setInterval(() => authApi.me().catch(() => {}), 5000)
}, { immediate: true })
// Voltou: se uma alteração tinha sido salva sem a tela conseguir se atualizar, atualiza agora.
watch(semConexao, (caiu, caiaAntes) => {
  if (caiaAntes && !caiu && ponto.desatualizado) atualizarTela()
})
onBeforeUnmount(() => {
  clearTimeout(timer)
  clearInterval(sonda)
})

const atualizando = ref(false)
async function atualizarTela() {
  atualizando.value = true
  try {
    await ponto.recarregarMes()
    await ponto.atualizarSaldos()
  } catch {
    // continua desatualizado: o aviso permanece
  } finally {
    atualizando.value = false
  }
}

const recarregarPagina = () => window.location.reload()
</script>

<template>
  <div class="pointer-events-none fixed inset-x-0 bottom-0 z-[60] flex flex-col items-center gap-2 px-3 pb-3" aria-live="polite">
    <p
      v-if="semConexao"
      role="status"
      class="pointer-events-auto flex max-w-xl items-center gap-2 rounded-[3px] border border-amber-700/40 bg-amber-50 px-3 py-2 text-sm text-amber-950 shadow-lg"
    >
      <span class="size-2 shrink-0 animate-pulse rounded-full bg-amber-500" aria-hidden="true" />
      <span>
        <b>Sem conexão com o sistema.</b> Tentando reconectar… O que aparece na tela pode estar desatualizado; evite
        salvar alterações até voltar.
      </span>
    </p>

    <p
      v-if="ponto.desatualizado"
      role="status"
      class="pointer-events-auto flex max-w-xl flex-wrap items-center gap-x-3 gap-y-1 rounded-[3px] border border-tinta/30 bg-cartao px-3 py-2 text-sm shadow-lg"
    >
      <span><b>A alteração foi salva</b>, mas a tela não pôde ser atualizada. Não repita a operação.</span>
      <button v-if="!semConexao" type="button" class="botao-secundario px-2! py-1! text-xs" :disabled="atualizando" @click="atualizarTela">
        {{ atualizando ? 'Atualizando…' : 'Atualizar agora' }}
      </button>
    </p>

    <p
      v-if="avisos.atualizado"
      role="alert"
      class="pointer-events-auto flex max-w-xl flex-wrap items-center gap-x-3 gap-y-1 rounded-[3px] border border-tinta/30 bg-cartao px-3 py-2 text-sm shadow-lg"
    >
      <span><b>O sistema foi atualizado.</b> Recarregue a página para continuar.</span>
      <button type="button" class="botao-primario px-2! py-1! text-xs" @click="recarregarPagina">Recarregar</button>
    </p>

    <p
      v-if="avisos.inesperado"
      role="alert"
      class="pointer-events-auto flex max-w-xl flex-wrap items-center gap-x-3 gap-y-1 rounded-[3px] border border-carimbo/40 bg-[#fbeeed] px-3 py-2 text-sm text-carimbo shadow-lg"
    >
      <span>{{ avisos.inesperado.mensagem }}</span>
      <span class="flex gap-2">
        <button
          v-if="avisos.inesperado.recarregar"
          type="button"
          class="botao-secundario px-2! py-1! text-xs"
          @click="recarregarPagina"
        >Recarregar a página</button>
        <button type="button" class="px-1 text-xs font-semibold underline underline-offset-4" @click="avisos.inesperado = null">fechar</button>
      </span>
    </p>
  </div>
</template>
