<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { mensagemDe } from '@/utils/erros'

/** Primeiro acesso (ou senha redefinida pelo administrador): a senha provisória precisa ser trocada. */
const auth = useAuthStore()
const ponto = usePontoStore()
const notificacoes = useNotificacoesStore()
const router = useRouter()

const atual = ref('')
const nova = ref('')
const confirmacao = ref('')
const erro = ref('')
const enviando = ref(false)
const campoAtual = ref(null)

onMounted(async () => {
  await nextTick()
  campoAtual.value?.focus()
})

const problemas = computed(() => {
  if (!atual.value) return 'Informe a senha provisória que você recebeu.'
  if (nova.value.length < 8) return 'A nova senha precisa ter pelo menos 8 caracteres.'
  if (nova.value === atual.value) return 'A nova senha precisa ser diferente da provisória.'
  if (nova.value !== confirmacao.value) return 'A confirmação não confere com a nova senha.'
  return null
})

async function trocar() {
  erro.value = problemas.value ?? ''
  if (erro.value) return
  enviando.value = true
  try {
    await auth.alterarSenha(atual.value, nova.value)
    router.replace(auth.rotaInicial())
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    enviando.value = false
  }
}

function sair() {
  // outra pessoa pode entrar na mesma aba: nada de quem saiu fica na memória
  ponto.limpar()
  notificacoes.limpar()
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <main class="grid min-h-dvh place-items-center px-4 py-10">
    <form class="cartao perfurado w-full max-w-sm animate-surgir py-6 pr-6" novalidate @submit.prevent="trocar">
      <p class="rotulo text-carimbo">Primeiro acesso</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%]">
        Crie sua senha
      </h1>
      <p class="mt-2 text-sm text-tinta-suave">
        Olá, {{ auth.usuario?.nome }}. Sua senha atual é provisória: troque-a para começar a usar o sistema.
      </p>

      <div class="mt-6 space-y-4">
        <label class="block">
          <span class="rotulo">Senha provisória</span>
          <input ref="campoAtual" v-model="atual" type="password" class="campo mt-1.5" autocomplete="current-password" />
        </label>
        <div>
          <label class="block">
            <span class="rotulo">Nova senha</span>
            <input v-model="nova" type="password" class="campo mt-1.5" autocomplete="new-password" aria-describedby="dica-senha" />
          </label>
          <span id="dica-senha" class="mt-1 block text-xs text-tinta-apagada">Pelo menos 8 caracteres.</span>
        </div>
        <label class="block">
          <span class="rotulo">Repita a nova senha</span>
          <input v-model="confirmacao" type="password" class="campo mt-1.5" autocomplete="new-password" />
        </label>
      </div>

      <p v-if="erro" role="alert" class="mt-4 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
        {{ erro }}
      </p>

      <button type="submit" class="botao-primario mt-6 w-full" :disabled="enviando">
        {{ enviando ? 'Salvando…' : 'Salvar e entrar' }}
      </button>
      <button type="button" class="mt-3 w-full text-center text-sm text-tinta-suave underline underline-offset-4 hover:text-tinta" @click="sair">
        Sair
      </button>
    </form>
  </main>
</template>
