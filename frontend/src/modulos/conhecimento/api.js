import { http } from '@/api/http'

/** Rotas da base de conhecimento (todas devolvem o conteúdo de "dados"). */
export const conhecimentoApi = {
  /** O que a pessoa logada pode: { pesquisar, curar, administrador }. */
  meuAcesso: () => http.get('/conhecimento/meu-acesso'),

  /** Administrador: todos os usuários ativos com o acesso de cada um à base. */
  acessos: () => http.get('/conhecimento/acessos'),

  /** Administrador: o que a pessoa pode fazer na base (curar inclui pesquisar). */
  definirAcesso: (usuarioId, pesquisar, curar) => http.put(`/conhecimento/acessos/${usuarioId}`, { pesquisar, curar }),
}
