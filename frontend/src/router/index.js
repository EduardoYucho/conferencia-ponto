import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import DashboardView from '@/views/DashboardView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { publica: true } },
    { path: '/', name: 'painel', component: DashboardView },
    { path: '/auditoria', name: 'auditoria', component: () => import('@/views/AuditoriaView.vue') },
    { path: '/conciliacao', name: 'conciliacao', component: () => import('@/views/ConciliacaoView.vue') },
    { path: '/ausencias', name: 'ausencias', component: () => import('@/views/AusenciasView.vue') },
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
