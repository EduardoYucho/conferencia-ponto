<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const login = ref('')
const senha = ref('')
const erro = ref('')
const enviando = ref(false)
const campoLogin = ref(null)

onMounted(async () => {
  await nextTick()
  campoLogin.value?.focus()
})

async function entrar() {
  erro.value = ''
  if (!login.value.trim() || !senha.value) {
    erro.value = 'Informe login e senha.'
    return
  }
  enviando.value = true
  try {
    await auth.login(login.value.trim(), senha.value)
    const destino = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect
      : auth.rotaInicial()
    router.replace(destino)
  } catch (e) {
    erro.value = e.message
    senha.value = ''
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <main class="grid min-h-dvh place-items-center px-4 py-10">
    <form class="cartao perfurado w-full max-w-sm animate-surgir py-6 pr-6" novalidate @submit.prevent="entrar">
      <p class="rotulo text-carimbo">Banco de horas · conferência do RH</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%]">
        Conferência de Ponto
      </h1>
      <p class="mt-2 text-sm text-tinta-suave">Entre com seu usuário para acessar o painel ou a auditoria.</p>

      <div class="mt-6 space-y-4">
        <label class="block">
          <span class="rotulo">Login</span>
          <input
            ref="campoLogin"
            v-model="login"
            class="campo mt-1.5 font-sans"
            autocomplete="username"
            autocapitalize="off"
            spellcheck="false"
            :aria-invalid="!!erro"
          />
        </label>
        <label class="block">
          <span class="rotulo">Senha</span>
          <input v-model="senha" type="password" class="campo mt-1.5" autocomplete="current-password" :aria-invalid="!!erro" />
        </label>
      </div>

      <p v-if="erro" role="alert" class="mt-4 rounded-[3px] border border-carimbo/40 bg-carimbo/10 px-3 py-2 text-sm text-carimbo">
        {{ erro }}
      </p>

      <button type="submit" class="botao-primario mt-6 w-full" :disabled="enviando">
        {{ enviando ? 'Entrando…' : 'Entrar' }}
      </button>
    </form>
  </main>
</template>
