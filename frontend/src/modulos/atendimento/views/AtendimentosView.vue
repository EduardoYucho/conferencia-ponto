<script setup>
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import EstadoDaTela from '@/components/EstadoDaTela.vue'

/**
 * Início do gerador de textos de atendimento. Nesta versão mostra só o que já existe e o que vem a seguir; o envio
 * do PDF do Digisac e a chave do Gemini chegam nas próximas etapas.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">Gerador de textos de atendimento</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Atendimentos</h1>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="!modulos.carregado" :erro="modulos.erro" carregando-texto="Conferindo o seu acesso…" @tentar="modulos.carregar()">
        <section v-if="!modulos.gerador" class="cartao px-5 py-4">
          <p class="font-semibold">O gerador de atendimentos não está liberado para você.</p>
          <p class="mt-1 text-sm text-tinta-suave">
            {{ auth.ehAdmin ? 'Libere em Acessos (inclusive para você mesmo).' : 'Peça ao administrador para liberar.' }}
          </p>
          <RouterLink v-if="auth.ehAdmin" :to="{ name: 'acessos-modulos' }" class="botao-secundario mt-3 inline-block">Abrir Acessos</RouterLink>
        </section>

        <section v-else class="grid gap-4 sm:grid-cols-2">
          <article class="cartao px-5 py-4">
            <p class="rotulo">Em construção</p>
            <h2 class="mt-1 font-sans text-lg font-bold">Novo atendimento</h2>
            <p class="mt-1 text-sm text-tinta-suave">
              Enviar o PDF da conversa do Digisac (os anexos são baixados sozinhos), as ligações, o vídeo e os prints,
              e gerar o resumo do atendimento ou o chamado para o desenvolvimento.
            </p>
          </article>
          <article class="cartao px-5 py-4">
            <p class="rotulo">Configuração</p>
            <h2 class="mt-1 font-sans text-lg font-bold">Minha chave do Gemini</h2>
            <p class="mt-1 text-sm text-tinta-suave">
              Cada pessoa usa a própria chave da API do Gemini, guardada cifrada no servidor. Cadastre e teste a sua
              antes de gerar os textos.
            </p>
            <RouterLink :to="{ name: 'chave-gemini' }" class="botao-secundario mt-3 inline-block">Abrir</RouterLink>
          </article>
        </section>
      </EstadoDaTela>
    </div>
  </div>
</template>
