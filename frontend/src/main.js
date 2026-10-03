import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { configurarAutenticacao } from './api/http'
import { instalarTratamentoGlobal } from './utils/erros'
import { useAuthStore } from './stores/auth'
import { usePontoStore } from './stores/ponto'
import { useNotificacoesStore } from './stores/notificacoes'
import { iniciarTema } from './utils/tema'
import '@fontsource-variable/figtree'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'
import '@fontsource/ibm-plex-mono/600.css'
import './style.css'

iniciarTema() // claro, escuro ou automático: a escolha de cada pessoa, guardada no navegador

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

const auth = useAuthStore(pinia)

const SESSAO_TERMINOU = 'Sua sessão terminou por tempo. Entre de novo para continuar de onde parou.'

/**
 * A sessão acabou sem a pessoa pedir (token vencido, acesso desativado): limpa tudo o que era dela, volta ao
 * login guardando a tela em que estava e explica o motivo. Único caminho para isso: requisição com 401, tempo
 * real com 401 e o relógio da expiração caem todos aqui.
 */
function encerrarSessao(aviso = SESSAO_TERMINOU) {
  if (!auth.token) return
  const atual = router.currentRoute.value
  auth.logout(aviso)
  usePontoStore(pinia).limpar()
  useNotificacoesStore(pinia).limpar()
  const voltarPara = !atual.meta.publica && atual.fullPath !== '/' ? { redirect: atual.fullPath } : {}
  router.replace({ name: 'login', query: voltarPara }).catch(() => {})
}

/** Senha provisória (o administrador redefiniu): troca antes de qualquer outra coisa. */
function irTrocarSenha() {
  if (!auth.usuario || auth.usuario.trocarSenha) return
  auth.usuario = { ...auth.usuario, trocarSenha: true }
  router.replace({ name: 'trocar-senha' }).catch(() => {})
}

configurarAutenticacao({
  obterToken: () => auth.token,
  obterUsuarioVisto: () => auth.loginConsultado(),
  aoPrecisarTrocarSenha: irTrocarSenha,
  // 401: sessão expirada ou acesso desativado pelo administrador (o servidor diz qual)
  aoNaoAutenticado: (codigo, mensagem) => encerrarSessao(codigo === 'ACESSO_DESATIVADO' && mensagem ? mensagem : undefined),
})
auth.definirAoExpirar(() => encerrarSessao())
usePontoStore(pinia).definirAoPerderSessao((_status, codigo) => (codigo === 'TROCAR_SENHA' ? irTrocarSenha() : encerrarSessao()))

app.use(router)
instalarTratamentoGlobal(app, router)
app.mount('#app')
