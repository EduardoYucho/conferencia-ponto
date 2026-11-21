<script setup>
/* global __VERSAO_DO_SISTEMA__, __VERSAO_GERADA_EM__ */
/**
 * Rodapé de todas as telas: quem desenvolveu e a versão (tecnologia.ano.número.subversão, gerada pelo
 * "ponto atualizar"; veja o README, seção Versão).
 */
const versao = __VERSAO_DO_SISTEMA__
const geradaEm = __VERSAO_GERADA_EM__
  ? new Date(__VERSAO_GERADA_EM__).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
  : ''
</script>

<template>
  <footer class="border-t border-linha px-4 py-3 text-center text-xs text-tinta-apagada sm:px-6">
    <span>Conferência de Ponto</span>
    <span aria-hidden="true"> · </span>
    <span>Desenvolvido por <span class="font-semibold text-tinta-suave">Eduardo Yucho</span></span>
    <span aria-hidden="true"> · </span>
    <span :title="geradaEm ? `Gerada em ${geradaEm}` : 'Compilada fora do ponto atualizar'">
      Versão <span class="font-mono text-tinta-suave">{{ versao }}</span>
    </span>
  </footer>
</template>
