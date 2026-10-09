import { http } from '@/api/http'

/** Rotas do gerador de atendimentos (todas devolvem o conteúdo de "dados"). */
export const atendimentoApi = {
  /** O que a pessoa logada pode: { gerador, administrador }. */
  meuAcesso: () => http.get('/atendimentos/meu-acesso'),

  /** Administrador: todos os usuários ativos com o acesso de cada um ao gerador. */
  acessos: () => http.get('/atendimentos/acessos'),

  /** Administrador: libera (true) ou retira (false) o gerador de uma pessoa. */
  definirAcesso: (usuarioId, gerador) => http.put(`/atendimentos/acessos/${usuarioId}`, { gerador }),

  /** A chave do Gemini da pessoa logada: { cadastrada, ultimosCaracteres, situacao, testadaEm, ... } (nunca a chave). */
  chaveGemini: () => http.get('/atendimentos/chave-gemini'),

  /** Testa a chave com o Google e, se ele aceitar, guarda cifrada. */
  salvarChaveGemini: (chave, nivelPagoConfirmado) =>
    http.put('/atendimentos/chave-gemini', { chave, nivelPagoConfirmado }),

  /** Testa de novo a chave guardada. */
  testarChaveGemini: () => http.post('/atendimentos/chave-gemini/testar'),

  apagarChaveGemini: () => http.delete('/atendimentos/chave-gemini'),
}
