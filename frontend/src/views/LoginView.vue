<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { mensagemDe } from '@/utils/erros'
import { TEMAS, definirTema, tema } from '@/utils/tema'
import Icone from '@/components/Icone.vue'

/**
 * Entrada no sistema. Os rótulos "Login" e "Senha" e o botão "Entrar" têm esses nomes exatos de propósito:
 * os testes automáticos encontram os campos por eles.
 */
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const login = ref('')
const senha = ref('')
const erro = ref('')
const enviando = ref(false)
const campoLogin = ref(null)
const ICONES_TEMA = { claro: 'sol', escuro: 'lua', auto: 'monitor' }

onMounted(async () => {
  await nextTick()
  campoLogin.value?.focus()
})

async function entrar() {
  erro.value = ''
  auth.avisoDeSaida = null
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
    erro.value = mensagemDe(e)
    senha.value = ''
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <main class="grid min-h-dvh place-items-center bg-fundo px-4 py-10">
    <div class="flex w-full max-w-sm animate-surgir flex-col gap-6">
      <header class="flex flex-col items-center gap-3 text-center">
        <span class="grid size-14 place-items-center rounded-2xl bg-botao text-sobre-botao"><Icone nome="relogio" tamanho="30" /></span>
        <div>
          <h1 class="titulo-pagina">Conferência de Ponto</h1>
          <p class="subtitulo-pagina">Entre para ver o seu ponto e o seu banco de horas.</p>
        </div>
      </header>

      <form class="cartao flex flex-col gap-4 p-6 shadow-sm" novalidate @submit.prevent="entrar">
        <p v-if="auth.avisoDeSaida && !erro" role="status" class="aviso-atencao flex items-start gap-2.5 text-[0.95rem]">
          <Icone nome="alerta" class="mt-0.5" /> <span>{{ auth.avisoDeSaida }}</span>
        </p>

        <div>
          <label for="campo-login" class="rotulo">Login</label>
          <input
            id="campo-login"
            ref="campoLogin"
            v-model="login"
            class="campo mt-1.5 min-h-12"
            autocomplete="username"
            autocapitalize="off"
            spellcheck="false"
            :aria-invalid="!!erro"
            :aria-describedby="erro ? 'erro-entrada' : undefined"
          />
        </div>
        <div>
          <label for="campo-senha" class="rotulo">Senha</label>
          <input
            id="campo-senha"
            v-model="senha"
            type="password"
            class="campo mt-1.5 min-h-12"
            autocomplete="current-password"
            :aria-invalid="!!erro"
            :aria-describedby="erro ? 'erro-entrada' : undefined"
          />
        </div>

        <p v-if="erro" id="erro-entrada" role="alert" class="aviso-erro flex items-start gap-2.5 text-[0.95rem]">
          <Icone nome="alerta" class="mt-0.5" /> <span>{{ erro }}</span>
        </p>

        <button type="submit" class="botao-primario mt-1 min-h-12 w-full text-base!" :disabled="enviando">
          {{ enviando ? 'Entrando…' : 'Entrar' }}
        </button>
      </form>

      <!-- Ainda não há menu nesta tela: a escolha do tema fica aqui, discreta -->
      <div class="flex flex-wrap items-center justify-center gap-x-3 gap-y-2">
        <span id="rotulo-aparencia" class="text-sm font-semibold text-texto-3">Aparência</span>
        <div class="flex gap-1 rounded-xl bg-neutro p-1" role="group" aria-labelledby="rotulo-aparencia">
          <button
            v-for="t in TEMAS"
            :key="t.valor"
            type="button"
            class="flex min-h-9 items-center gap-1.5 rounded-lg px-2.5 text-sm font-semibold transition-colors"
            :class="tema === t.valor ? 'bg-superficie text-texto shadow-sm' : 'text-texto-3 hover:text-texto'"
            :aria-pressed="tema === t.valor"
            :title="t.valor === 'auto' ? 'Acompanha o tema do computador ou do celular' : undefined"
            @click="definirTema(t.valor)"
          >
            <Icone :nome="ICONES_TEMA[t.valor]" tamanho="16" />
            {{ t.rotulo }}
          </button>
        </div>
      </div>
    </div>
  </main>
</template>
