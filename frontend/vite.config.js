import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  // A versão do rodapé: o "expedio atualizar" passa a gerada (1.1.001.001); fora dele, "desenvolvimento"
  define: {
    __VERSAO_DO_SISTEMA__: JSON.stringify(process.env.EXPEDIO_VERSAO || 'desenvolvimento'),
    __VERSAO_GERADA_EM__: JSON.stringify(process.env.EXPEDIO_VERSAO_GERADA_EM || ''),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // Em desenvolvimento, /api é encaminhado ao Spring Boot (sem CORS no navegador)
    proxy: {
      '/api': 'http://localhost:8080',
      // página de coleta e worker dos backups de clientes (servidos pelo sistema)
      '/c/': 'http://localhost:8080',
    },
  },
})
