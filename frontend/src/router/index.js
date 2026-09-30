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
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/** Toda rota exige sessão; quem já está logado não volta ao login. */
router.beforeEach((destino) => {
  const auth = useAuthStore()
  if (destino.meta.publica) {
    return auth.autenticado ? auth.rotaInicial() : true
  }
  if (!auth.autenticado) {
    return { name: 'login', query: destino.fullPath !== '/' ? { redirect: destino.fullPath } : {} }
  }
  return true
})
