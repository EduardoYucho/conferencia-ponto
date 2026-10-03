<script setup>
/**
 * Ícones de traço usados no menu e nos botões (sempre ao lado de um texto: ícone sozinho não explica nada).
 * Desenhados numa grade de 20×20; a cor é a do texto ao redor.
 */
defineProps({
  nome: { type: String, required: true },
  tamanho: { type: [Number, String], default: 20 },
})

const TRACOS = {
  inicio: 'M3 9.5 10 3l7 6.5V17h-5v-5H8v5H3z',
  calendario: 'M5 4.5h10a2 2 0 0 1 2 2V15a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2zM3 8.5h14M7 2.5v4M13 2.5v4',
  relogio: 'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM10 6v4l2.6 2',
  comparar: 'M4 6.5h10l-2.5-2.5M16 13.5H6l2.5 2.5',
  sol: 'M10 6.8a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0-6.4zM10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M4.7 15.3l1.4-1.4M13.9 6.1l1.4-1.4',
  lua: 'M16.5 11.6A6.8 6.8 0 0 1 8.4 3.5a6.8 6.8 0 1 0 8.1 8.1z',
  monitor: 'M3.5 4.5h13v9h-13zM7 17h6M10 13.5V17',
  equipe: 'M7.5 4.2a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6zM2.5 16.5c.4-2.8 2.4-4.3 5-4.3s4.6 1.5 5 4.3M14 5.6a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4zM14.2 12.3c1.9.2 3 1.4 3.3 3.4',
  usuario: 'M10 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM4 17c.5-3 2.8-4.6 6-4.6s5.5 1.6 6 4.6',
  lista: 'M7 5.5h9.5M7 10h9.5M7 14.5h9.5M3.4 5.5h.2M3.4 10h.2M3.4 14.5h.2',
  sino: 'M5.5 13.5V9a4.5 4.5 0 0 1 9 0v4.5l1.5 2h-12zM8.3 17.3a1.8 1.8 0 0 0 3.4 0',
  mais: 'M4.5 10h.1M10 10h.1M15.5 10h.1',
  sair: 'M8 4H4.5v12H8M12.5 6.5 16 10l-3.5 3.5M16 10H8',
  enviar: 'M10 13V3.5M6 7l4-4 4 4M3.5 13v3.5h13V13',
  baixar: 'M10 3.5V13M6 9.5l4 4 4-4M3.5 16.5h13',
  somar: 'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM6.5 10h7M10 6.5v7',
  lapis: 'M4 16l.8-3.4 8.6-8.6a1.5 1.5 0 0 1 2.1 0l.5.5a1.5 1.5 0 0 1 0 2.1L7.4 15.2zM11.5 5.9l2.6 2.6',
  lixeira: 'M4 6h12M8 6V4h4v2M6 6l.7 10h6.6L14 6M8.5 9v4.5M11.5 9v4.5',
  certo: 'M4.5 10.5l3.5 3.5 7.5-8',
  alerta: 'M10 3.2 17.3 16H2.7zM10 8v3.6M10 14h.01',
  fechar: 'M5 5l10 10M15 5L5 15',
  esquerda: 'M12.5 4.5 7 10l5.5 5.5',
  direita: 'M7.5 4.5 13 10l-5.5 5.5',
  abaixo: 'M4.5 7.5 10 13l5.5-5.5',
  documento: 'M6 3h5.5L15 6.5V17H6zM11.5 3v3.5H15M8.5 10.5h4M8.5 13.5h4',
  pasta: 'M3 6.5V15a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8a1 1 0 0 0-1-1h-5.5L9 5H4a1 1 0 0 0-1 1.5z',
  externo: 'M8 5H5v10h10v-3M11 4h5v5M16 4l-7 7',
  atualizar: 'M15.5 8A6 6 0 0 0 4.6 6.5M4.5 3.5v3h3M4.5 12a6 6 0 0 0 10.9 1.5M15.5 16.5v-3h-3',
  engrenagem: 'M10 7.2a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6zM10 2.5v2.2M10 15.3v2.2M2.5 10h2.2M15.3 10h2.2M4.7 4.7l1.6 1.6M13.7 13.7l1.6 1.6M4.7 15.3l1.6-1.6M13.7 6.3l1.6-1.6',
  cadeado: 'M5.5 9h9v7.5h-9zM7.5 9V6.5a2.5 2.5 0 0 1 5 0V9',
  subtrair: 'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM6.5 10h7',
}
</script>

<template>
  <svg
    :width="tamanho"
    :height="tamanho"
    viewBox="0 0 20 20"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    class="shrink-0"
  >
    <path :d="TRACOS[nome] ?? ''" />
  </svg>
</template>
