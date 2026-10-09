<script setup>
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { useAuthStore } from '@/stores/auth'
import EstadoDaTela from '@/components/EstadoDaTela.vue'

/**
 * Início da base de conhecimento. Os registros nascem dos textos confirmados no gerador; a pesquisa chega depois
 * dele.
 */
const auth = useAuthStore()
const modulos = useAcessoModulosStore()
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <p class="rotulo">Erros e solicitações já atendidos</p>
      <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Base de conhecimento</h1>
    </header>

    <div class="mt-6">
      <EstadoDaTela :carregando="!modulos.carregado" :erro="modulos.erro" carregando-texto="Conferindo o seu acesso…" @tentar="modulos.carregar()">
        <section v-if="!modulos.pesquisar" class="cartao px-5 py-4">
          <p class="font-semibold">A base de conhecimento não está liberada para você.</p>
          <p class="mt-1 text-sm text-tinta-suave">
            {{ auth.ehAdmin ? 'Libere em Acessos (inclusive para você mesmo).' : 'Peça ao administrador para liberar.' }}
          </p>
          <RouterLink v-if="auth.ehAdmin" :to="{ name: 'acessos-modulos' }" class="botao-secundario mt-3 inline-block">Abrir Acessos</RouterLink>
        </section>

        <section v-else class="cartao px-5 py-4">
          <p class="rotulo">Em construção</p>
          <h2 class="mt-1 font-sans text-lg font-bold">Pesquisa</h2>
          <p class="mt-1 text-sm text-tinta-suave">
            Os registros nascem dos textos confirmados no gerador de atendimentos: cliente, tela, erro, causa,
            resolução e a situação no desenvolvimento. A pesquisa (por código da tela, mensagem de erro, cliente e
            por significado) chega depois do gerador.
            <template v-if="modulos.curar"> Você também pode curar a base: editar os registros e mudar a situação.</template>
          </p>
        </section>
      </EstadoDaTela>
    </div>
  </div>
</template>
