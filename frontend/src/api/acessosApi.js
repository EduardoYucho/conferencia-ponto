import { http } from '@/api/http'

/** Perfis de acesso e o acesso de cada pessoa (só com "Perfis e acessos"). Todas devolvem o conteúdo de "dados". */
export const acessosApi = {
  /** As permissões que existem, em grupos: [{ nome, permissoes: [{ codigo, nome, descricao, requer }] }]. */
  permissoes: () => http.get('/acessos/permissoes'),
  /** Os perfis: [{ id, nome, descricao, todas, doSistema, permissoes, pessoas: [{ id, nome, login, ativo }], ... }]. */
  perfis: () => http.get('/acessos/perfis'),
  /** @param {{ nome, descricao, permissoes: string[] }} perfil */
  criarPerfil: (perfil) => http.post('/acessos/perfis', perfil),
  alterarPerfil: (id, perfil) => http.put(`/acessos/perfis/${id}`, perfil),
  excluirPerfil: (id) => http.delete(`/acessos/perfis/${id}`),
  /** { usuarioId, nome, login, ativo, perfis: [id], ajustes: [{ permissao, liberada }], permissoes: [{ permissao, pode, perfis, ajuste, faltando }] }. */
  acessoDe: (usuarioId) => http.get(`/acessos/usuarios/${usuarioId}`),
  /** Troca os perfis e os ajustes da pessoa por estes. */
  definirAcesso: (usuarioId, perfis, ajustes) => http.put(`/acessos/usuarios/${usuarioId}`, { perfis, ajustes }),
  /** As últimas mudanças: [{ quando, quem, usuarioId, pessoa, perfilId, descricao }]. */
  historico: (usuarioId = null, limite = 30) =>
    http.get('/acessos/historico', { params: usuarioId ? { usuario: usuarioId, limite } : { limite } }),
}
