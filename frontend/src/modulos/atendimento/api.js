import { http } from '@/api/http'

/** Rotas do gerador de atendimentos (todas devolvem o conteúdo de "dados"). */
export const atendimentoApi = {
  /** O que a pessoa logada pode: { gerador, administrador }. */
  meuAcesso: () => http.get('/atendimentos/meu-acesso'),

  /** Administrador: todos os usuários ativos com o acesso de cada um ao gerador. */
  acessos: () => http.get('/atendimentos/acessos'),

  /** Administrador: libera (true) ou retira (false) o gerador de uma pessoa. */
  definirAcesso: (usuarioId, gerador) => http.put(`/atendimentos/acessos/${usuarioId}`, { gerador }),
}
