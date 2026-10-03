<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { conexao } from '@/api/http'
import { authApi } from '@/api/authApi'
import { avisos } from '@/utils/erros'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { aviso, avisar, fecharAviso } from '@/utils/avisar'
import Icone from '@/components/Icone.vue'

/**
 * Avisos que valem para qualquer tela, sempre no mesmo lugar (rodapé):
 * - o resultado da última ação (utils/avisar);
 * - sem conexão com o sistema (some sozinho quando volta);
 * - a alteração foi salva, mas a tela não pôde se atualizar;
 * - o sistema foi atualizado e a aba precisa ser recarregada;
 * - algo deu errado e nenhuma tela tratou.
 */
defineProps({
  /** A tela tem o menu (lateral no computador, barra de baixo no celular): os avisos desviam dele. */
  comMenu: { type: Boolean, default: false },
})

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
// Uma alteração foi salva sem a tela conseguir se atualizar: assim que o sistema volta a responder (e, depois, a
// cada poucos segundos) a tela tenta de novo sozinha — a pessoa não precisa achar o botão "Atualizar agora".
// No máximo uma tentativa a cada 4 s: uma falha parcial não vira uma rajada de pedidos.
const atualizando = ref(false)
let ultimaTentativa = 0
async function atualizarTela() {
  if (atualizando.value) return
  atualizando.value = true
  ultimaTentativa = Date.now()
  try {
    await ponto.atualizarTudo()
  } catch {
    // continua desatualizado: o aviso permanece
  } finally {
    atualizando.value = false
  }
}
function tentarSozinho() {
  if (ponto.desatualizado && !semConexao.value && Date.now() - ultimaTentativa > 4000) atualizarTela()
}
watch(semConexao, (caiu, caiaAntes) => caiaAntes && !caiu && tentarSozinho())
let repeticao = null
watch(() => auth.autenticado && ponto.desatualizado, (pendente) => {
  clearInterval(repeticao)
  if (pendente) repeticao = setInterval(tentarSozinho, 5000)
}, { immediate: true })
onBeforeUnmount(() => {
  clearTimeout(timer)
  clearInterval(sonda)
  clearInterval(repeticao)
})

// Comprovantes que chegam pela pasta monitorada (ou que não puderam ser importados) avisam em qualquer tela.
watch(() => ponto.ultimoEvento, (evento) => {
  if (!evento) return
  if (evento.tipo === 'jornada-atualizada' && evento.origem === 'COMPROVANTE_PDF') {
    avisar(`Comprovante importado: ${evento.mensagem}`)
  } else if (evento.tipo === 'comprovante-nao-importado') {
    avisar(`${evento.nomeArquivo}: ${evento.mensagem}`, evento.status === 'DUPLICADO' ? 'info' : 'erro')
  }
})

const recarregarPagina = () => window.location.reload()
</script>

<template>
  <div
    class="pointer-events-none fixed inset-x-0 z-[60] flex flex-col items-center gap-2 px-3 pb-3 lg:bottom-0"
    :class="comMenu ? 'bottom-[calc(3.5rem+env(safe-area-inset-bottom))] lg:pl-64' : 'bottom-0'"
    aria-live="polite"
  >
    <!-- Aviso rápido da última ação ("Batida registrada", "Não foi possível...") -->
    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-3 opacity-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0"
    >
      <p
        v-if="aviso.atual"
        :key="aviso.atual.chave"
        :role="aviso.atual.tipo === 'erro' ? 'alert' : 'status'"
        class="pointer-events-auto flex w-full max-w-xl items-start gap-3 rounded-xl px-4 py-3 text-[0.95rem] font-semibold shadow-xl"
        :class="{
          'bg-negativo-solido text-white': aviso.atual.tipo === 'erro',
          'bg-positivo-solido text-white': aviso.atual.tipo === 'ok',
          'border border-borda-forte bg-superficie text-texto': aviso.atual.tipo === 'info',
        }"
      >
        <Icone :nome="aviso.atual.tipo === 'erro' ? 'alerta' : aviso.atual.tipo === 'ok' ? 'certo' : 'sino'" class="mt-0.5" />
        <span class="min-w-0 flex-1">{{ aviso.atual.texto }}</span>
        <!-- o erro não some sozinho: fica até a pessoa ler e fechar -->
        <button
          v-if="aviso.atual.tipo === 'erro'"
          type="button"
          class="-my-1 -mr-2 grid size-9 shrink-0 place-items-center rounded-lg hover:bg-white/15"
          aria-label="Fechar o aviso"
          @click="fecharAviso"
        ><Icone nome="fechar" tamanho="18" /></button>
      </p>
    </Transition>

    <p
      v-if="semConexao"
      role="status"
      class="aviso-atencao pointer-events-auto flex max-w-xl items-start gap-2.5 shadow-xl"
    >
      <span class="mt-1.5 size-2 shrink-0 animate-pulse rounded-full bg-atencao-solido" aria-hidden="true" />
      <span>
        <b>Sem conexão com o sistema.</b> Tentando reconectar… O que aparece na tela pode estar desatualizado; evite
        salvar alterações até voltar.
      </span>
    </p>

    <p
      v-if="ponto.desatualizado"
      role="status"
      class="pointer-events-auto flex max-w-xl flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border border-borda-forte bg-superficie px-4 py-3 text-[0.95rem] shadow-xl"
    >
      <span class="min-w-0 flex-1"><b>A alteração foi salva</b>, mas a tela não pôde ser atualizada. Não repita a operação.</span>
      <button v-if="!semConexao" type="button" class="botao-secundario px-3! py-1.5!" :disabled="atualizando" @click="atualizarTela">
        {{ atualizando ? 'Atualizando…' : 'Atualizar agora' }}
      </button>
    </p>

    <p
      v-if="avisos.atualizado"
      role="alert"
      class="pointer-events-auto flex max-w-xl flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border border-borda-forte bg-superficie px-4 py-3 text-[0.95rem] shadow-xl"
    >
      <span class="min-w-0 flex-1"><b>O sistema foi atualizado.</b> Recarregue a página para continuar.</span>
      <button type="button" class="botao-primario px-3! py-1.5!" @click="recarregarPagina">Recarregar</button>
    </p>

    <p
      v-if="avisos.inesperado"
      role="alert"
      class="aviso-erro pointer-events-auto flex max-w-xl flex-wrap items-center gap-x-3 gap-y-2 shadow-xl"
    >
      <span class="min-w-0 flex-1 text-[0.95rem]">{{ avisos.inesperado.mensagem }}</span>
      <span class="flex items-center gap-2">
        <button
          v-if="avisos.inesperado.recarregar"
          type="button"
          class="botao-secundario px-3! py-1.5!"
          @click="recarregarPagina"
        >Recarregar a página</button>
        <button type="button" class="px-1 text-sm font-bold underline underline-offset-4" @click="avisos.inesperado = null">Fechar</button>
      </span>
    </p>
  </div>
</template>
