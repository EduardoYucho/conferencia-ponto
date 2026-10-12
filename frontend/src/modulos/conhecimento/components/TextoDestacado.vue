<script setup>
/**
 * Um texto em partes, com o que bateu na pesquisa destacado. As partes vêm do servidor e são montadas como texto
 * (nunca como HTML).
 */
defineProps({
  partes: { type: Array, default: () => [] },
})
</script>

<template>
  <template v-for="(parte, i) in partes" :key="i"><mark v-if="parte.destaque" class="destaque">{{ parte.texto }}</mark><template v-else>{{ parte.texto }}</template></template>
</template>

<style scoped>
.destaque {
  background: color-mix(in srgb, var(--color-carimbo) 18%, transparent);
  color: inherit;
  border-radius: 2px;
  padding: 0 1px;
  font-weight: 600;
}
</style>
