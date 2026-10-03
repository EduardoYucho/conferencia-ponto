<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePontoStore } from '@/stores/ponto'
import { useNotificacoesStore } from '@/stores/notificacoes'
import { mensagemDe } from '@/utils/erros'
import { TEMAS, definirTema, tema } from '@/utils/tema'
import Icone from '@/components/Icone.vue'

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
const ICONES_TEMA = { claro: 'sol', escuro: 'lua', auto: 'monitor' }

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
  <main class="grid min-h-dvh place-items-center bg-fundo px-4 py-10">
    <div class="flex w-full max-w-sm animate-surgir flex-col gap-6">
      <header class="flex flex-col items-center gap-3 text-center">
        <span class="grid size-14 place-items-center rounded-2xl bg-botao text-sobre-botao"><Icone nome="relogio" tamanho="30" /></span>
        <div>
          <h1 class="titulo-pagina">Crie sua senha</h1>
          <p class="subtitulo-pagina">
            Olá, {{ auth.usuario?.nome }}. A senha que você recebeu é provisória: troque por uma só sua para começar a usar o sistema.
          </p>
        </div>
      </header>

      <form class="cartao flex flex-col gap-4 p-6 shadow-sm" novalidate @submit.prevent="trocar">
        <div>
          <label for="senha-provisoria" class="rotulo">Senha provisória</label>
          <input
            id="senha-provisoria"
            ref="campoAtual"
            v-model="atual"
            type="password"
            class="campo mt-1.5 min-h-12"
            autocomplete="current-password"
            :aria-describedby="erro ? 'erro-troca' : undefined"
          />
        </div>
        <div>
          <label for="senha-nova" class="rotulo">Nova senha</label>
          <input
            id="senha-nova"
            v-model="nova"
            type="password"
            class="campo mt-1.5 min-h-12"
            autocomplete="new-password"
            aria-describedby="dica-senha"
          />
          <p id="dica-senha" class="mt-1.5 text-sm text-texto-3">Pelo menos 8 caracteres.</p>
        </div>
        <div>
          <label for="senha-repetida" class="rotulo">Repita a nova senha</label>
          <input
            id="senha-repetida"
            v-model="confirmacao"
            type="password"
            class="campo mt-1.5 min-h-12"
            autocomplete="new-password"
          />
        </div>

        <p v-if="erro" id="erro-troca" role="alert" class="aviso-erro flex items-start gap-2.5 text-[0.95rem]">
          <Icone nome="alerta" class="mt-0.5" /> <span>{{ erro }}</span>
        </p>

        <button type="submit" class="botao-primario mt-1 min-h-12 w-full text-base!" :disabled="enviando">
          {{ enviando ? 'Salvando…' : 'Salvar e entrar' }}
        </button>
        <button type="button" class="botao-secundario min-h-11 w-full" :disabled="enviando" @click="sair">
          <Icone nome="sair" tamanho="18" /> Sair
        </button>
      </form>

      <!-- Sem menu nesta tela: a escolha do tema fica aqui, discreta -->
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
