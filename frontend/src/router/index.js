import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import InicioView from '@/views/InicioView.vue'
// Fora do carregamento sob demanda: é para o login que a aba volta quando algo dá errado (sessão vencida,
// sistema atualizado), e nessa hora os arquivos antigos das outras telas podem não existir mais.
import LoginView from '@/views/LoginView.vue'

export const router = createRouter({
  history: createWebHistory(),
  // links para uma seção (ex.: /usuarios#google) rolam até ela
  scrollBehavior: (destino) => (destino.hash ? { el: destino.hash, behavior: 'smooth' } : undefined),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { publica: true } },
    { path: '/', name: 'painel', component: InicioView },
    { path: '/ponto', name: 'meu-ponto', component: () => import('@/views/MeuPontoView.vue') },
    { path: '/banco', name: 'banco', component: () => import('@/views/BancoView.vue') },
    // endereço antigo (favoritos, links em avisos): a conferência dia a dia agora fica em "Meu ponto"
    { path: '/auditoria', name: 'auditoria', redirect: (destino) => ({ name: 'meu-ponto', query: destino.query }) },
    { path: '/conciliacao', name: 'conciliacao', component: () => import('@/views/ConciliacaoView.vue') },
    { path: '/ausencias', name: 'ausencias', component: () => import('@/views/AusenciasView.vue') },
    { path: '/equipe', name: 'equipe', component: () => import('@/views/EquipeView.vue') },
    { path: '/logs', name: 'logs', component: () => import('@/views/LogsView.vue'), meta: { admin: true } },
    { path: '/conta', name: 'conta', component: () => import('@/views/MinhaContaView.vue') },
    { path: '/usuarios', name: 'usuarios', component: () => import('@/views/UsuariosView.vue'), meta: { admin: true } },
    {
      path: '/trocar-senha',
      name: 'trocar-senha',
      component: () => import('@/views/TrocarSenhaView.vue'),
      meta: { semBarra: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/**
 * Toda rota exige sessão; quem já está logado não volta ao login. Senha provisória: só a troca de senha.
 * Cadastro de usuários: só o administrador.
 */
router.beforeEach((destino) => {
  const auth = useAuthStore()
  if (destino.meta.publica) {
    return auth.autenticado ? auth.rotaInicial() : true
  }
  if (!auth.autenticado) {
    return { name: 'login', query: destino.fullPath !== '/' ? { redirect: destino.fullPath } : {} }
  }
  if (auth.precisaTrocarSenha) {
    return destino.name === 'trocar-senha' ? true : { name: 'trocar-senha' }
  }
  if (destino.name === 'trocar-senha') return auth.rotaInicial()
  if (destino.meta.admin && !auth.ehAdmin) return auth.rotaInicial()
  return true
})
