/**
 * Telas dos módulos de atendimentos e base de conhecimento (carregadas sob demanda, como as do ponto). O router
 * só importa esta lista: as telas, as chamadas à API e o estado ficam todos em src/modulos.
 */
export const rotasDosModulos = [
  {
    path: '/atendimentos',
    name: 'atendimentos',
    component: () => import('./atendimento/views/AtendimentosView.vue'),
  },
  {
    path: '/atendimentos/chave-gemini',
    name: 'chave-gemini',
    component: () => import('./atendimento/views/MinhaChaveGeminiView.vue'),
  },
  {
    path: '/atendimentos/novo',
    name: 'atendimento-novo',
    component: () => import('./atendimento/views/NovoAtendimentoView.vue'),
  },
  {
    path: '/atendimentos/:id',
    name: 'atendimento',
    component: () => import('./atendimento/views/AtendimentoView.vue'),
    props: true,
  },
  {
    path: '/base-conhecimento',
    name: 'base-conhecimento',
    component: () => import('./conhecimento/views/BaseConhecimentoView.vue'),
  },
  {
    path: '/acessos',
    name: 'acessos-modulos',
    component: () => import('./AcessosView.vue'),
    meta: { admin: true },
  },
]
