<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { pontoApi } from '@/api/pontoApi'
import { mensagemDe } from '@/utils/erros'
import { dataCurta } from '@/utils/tempo'

/**
 * Um comprovante que entrou no ponto da pessoa errada (o PDF da colega caiu na pasta dela). O PDF não diz de quem é,
 * então quem corrige é gente:
 *  - a própria pessoa tira do ponto dela ("não é meu"): a batida sai, o PDF fica guardado e a pasta dela não o
 *    importa de novo; a pessoa certa pode importá-lo;
 *  - quem configura o ponto da equipe pode também mandar o PDF direto para a pessoa certa.
 * Tudo fica no histórico do dia, com o motivo. Mês conferido não muda.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** O PDF arquivado (ComprovanteArquivoResponse) */
  comprovante: { type: Object, default: null },
  /** De quem é o ponto em que ele está: { login, nome } */
  dono: { type: Object, default: null },
  /** É o próprio ponto de quem está vendo (pode tirar dele) */
  proprio: { type: Boolean, default: false },
  /** Quem está vendo configura o ponto da equipe (pode mover para outra pessoa) */
  podeMover: { type: Boolean, default: false },
  /** As pessoas que podem receber o comprovante: [{ login, nome }] */
  pessoas: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'concluido'])

const SO_TIRAR = ''
const destino = ref(SO_TIRAR)
const motivo = ref('')
const enviando = ref(false)
const erro = ref('')
const campoMotivo = ref(null)

const outras = computed(() => props.pessoas.filter((p) => p.login !== props.dono?.login))
const hora = computed(() => (props.comprovante?.dataHoraBatida ?? '').slice(11, 19))
const dia = computed(() => (props.comprovante?.dataHoraBatida ?? '').slice(0, 10))
const motivoOk = computed(() => motivo.value.trim().length >= 5)

watch(
  () => props.modelValue,
  async (aberto) => {
    if (!aberto) return
    destino.value = props.proprio ? SO_TIRAR : (outras.value[0]?.login ?? SO_TIRAR)
    motivo.value = ''
    erro.value = ''
    await nextTick()
    campoMotivo.value?.focus()
  },
  { immediate: true },
)

function fechar() {
  if (!enviando.value) emit('update:modelValue', false)
}
function aoTeclar(evento) {
  if (evento.key === 'Escape' && props.modelValue) fechar()
}
window.addEventListener('keydown', aoTeclar)
onBeforeUnmount(() => window.removeEventListener('keydown', aoTeclar))

async function enviar() {
  if (enviando.value) return
  if (!motivoOk.value) {
    erro.value = 'Escreva por que o comprovante sai deste ponto (pelo menos 5 letras).'
    return
  }
  if (destino.value === SO_TIRAR && !props.proprio) {
    erro.value = 'Escolha a pessoa que vai receber o comprovante.'
    return
  }
  enviando.value = true
  erro.value = ''
  try {
    const resposta = destino.value === SO_TIRAR
      ? await pontoApi.desfazerImportacao(props.comprovante.id, motivo.value.trim())
      : await pontoApi.moverComprovante(props.comprovante.id, destino.value, motivo.value.trim())
    emit('concluido', resposta)
    emit('update:modelValue', false)
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      leave-active-class="transition duration-150 ease-in"
      leave-to-class="opacity-0"
    >
      <div
        v-if="modelValue && comprovante"
        class="fixed inset-0 z-50 flex items-end justify-center bg-tinta/45 p-0 backdrop-blur-[2px] sm:items-center sm:p-6"
        @mousedown.self="fechar"
      >
        <form
          role="dialog"
          aria-modal="true"
          aria-labelledby="titulo-comprovante-errado"
          class="cartao perfurado w-full max-w-lg animate-surgir rounded-b-none sm:rounded-[3px]"
          novalidate
          @submit.prevent="enviar"
        >
          <header class="flex items-start justify-between gap-4 border-b border-dashed border-linha px-5 pt-5 pb-4">
            <div>
              <p class="rotulo text-carimbo">Comprovante de {{ dataCurta(dia) }} às {{ hora }}</p>
              <h2 id="titulo-comprovante-errado" class="mt-1 font-sans text-xl font-extrabold tracking-tight [font-stretch:88%]">
                {{ proprio ? 'Este comprovante não é seu?' : `Comprovante no ponto de ${dono?.nome}` }}
              </h2>
              <p class="mt-1 text-sm text-tinta-suave">
                A batida que ele trouxe sai do dia (se ela veio do botão, só o PDF sai) e o PDF fica guardado à parte.
                Fica no histórico do dia, com o motivo.
              </p>
            </div>
            <button type="button" class="-mr-1 rounded-[3px] p-1.5 text-tinta-suave hover:bg-papel-escuro hover:text-tinta" aria-label="Fechar" @click="fechar">
              <svg viewBox="0 0 20 20" class="size-5" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M5 5l10 10M15 5L5 15" /></svg>
            </button>
          </header>

          <div class="space-y-4 px-5 py-5">
            <p class="text-xs text-tinta-suave">Arquivo: {{ comprovante.nomeOriginal }}</p>

            <div v-if="podeMover && outras.length">
              <label for="destino-comprovante" class="rotulo">Para onde vai</label>
              <select id="destino-comprovante" v-model="destino" class="campo mt-1.5">
                <option v-if="proprio" :value="SO_TIRAR">Só tirar do meu ponto (a pessoa certa importa depois)</option>
                <option v-for="p in outras" :key="p.login" :value="p.login">Para o ponto de {{ p.nome }}</option>
              </select>
            </div>
            <p v-else-if="proprio" class="text-sm">
              Ele sai do seu ponto e a sua pasta não o importa de novo. A pessoa certa pode importá-lo pela pasta dela ou
              pelo botão "Enviar PDFs".
            </p>

            <div>
              <label for="motivo-comprovante" class="rotulo">Motivo</label>
              <input
                id="motivo-comprovante"
                ref="campoMotivo"
                v-model="motivo"
                type="text"
                maxlength="200"
                class="campo mt-1.5 font-sans"
                placeholder="Ex.: é da Maria, caiu na minha pasta"
              />
            </div>

            <p v-if="erro" role="alert" class="rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">{{ erro }}</p>
          </div>

          <footer class="flex flex-col-reverse gap-2 border-t border-dashed border-linha px-5 py-4 sm:flex-row sm:justify-end">
            <button type="button" class="botao-secundario" :disabled="enviando" @click="fechar">Cancelar</button>
            <button type="submit" class="botao-primario" :disabled="enviando || !motivoOk">
              {{ enviando ? 'Salvando…' : destino === SO_TIRAR ? 'Tirar do meu ponto' : 'Mover o comprovante' }}
            </button>
          </footer>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>
