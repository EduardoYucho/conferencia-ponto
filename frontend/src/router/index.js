import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import DashboardView from '@/views/DashboardView.vue'
// Fora do carregamento sob demanda: é para o login que a aba volta quando algo dá errado (sessão vencida,
// sistema atualizado), e nessa hora os arquivos antigos das outras telas podem não existir mais.
import LoginView from '@/views/LoginView.vue'
// telas dos módulos de atendimentos e base de conhecimento (ficam todas em src/modulos)
import { rotasDosModulos } from '@/modulos/rotas'

export const router = createRouter({
  history: createWebHistory(),
  // links para uma seção (ex.: /usuarios#google) rolam até ela
  scrollBehavior: (destino) => (destino.hash ? { el: destino.hash, behavior: 'smooth' } : undefined),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { publica: true } },
    { path: '/', name: 'painel', component: DashboardView },
    { path: '/auditoria', name: 'auditoria', component: () => import('@/views/AuditoriaView.vue') },
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
    ...rotasDosModulos,
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
