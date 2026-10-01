import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { configurarAutenticacao } from './api/http'
import { useAuthStore } from './stores/auth'
import { usePontoStore } from './stores/ponto'
import '@fontsource-variable/archivo/wdth.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'
import '@fontsource/ibm-plex-mono/600.css'
import './style.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

const auth = useAuthStore(pinia)
configurarAutenticacao({
  obterToken: () => auth.token,
  obterUsuarioVisto: () => auth.loginConsultado(),
  // senha provisória (o admin redefiniu): troca antes de qualquer outra coisa
  aoPrecisarTrocarSenha: () => {
    if (!auth.usuario || auth.usuario.trocarSenha) return
    auth.usuario = { ...auth.usuario, trocarSenha: true }
    router.replace({ name: 'trocar-senha' })
  },
  // 401: sessão expirada/ inválida -> volta ao login preservando a rota atual
  aoNaoAutenticado: () => {
    if (!auth.token) return
    auth.logout()
    usePontoStore(pinia).limpar()
    const atual = router.currentRoute.value
    router.replace({ name: 'login', query: atual.meta.publica ? {} : { redirect: atual.fullPath } })
  },
})

app.use(router)
app.mount('#app')
