import { onBeforeUnmount, onMounted, ref } from 'vue'

/** Relógio reativo (atualiza a cada `intervaloMs`). */
export function useRelogio(intervaloMs = 1000) {
  const agora = ref(new Date())
  let timer = null

  onMounted(() => {
    timer = setInterval(() => {
      agora.value = new Date()
    }, intervaloMs)
  })

  onBeforeUnmount(() => clearInterval(timer))

  return agora
}
