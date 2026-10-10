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

  /** Os atendimentos da pessoa logada, do mais novo para o mais antigo. */
  atendimentos: () => http.get('/atendimentos'),

  /** Conversa lida, anexos e validade dos links (atendimento de outra pessoa: 404). */
  atendimento: (id) => http.get(`/atendimentos/${id}`),

  /**
   * Envia o PDF da conversa do Digisac em fluxo (sem multipart). O nome vai codificado no cabeçalho, porque
   * cabeçalho HTTP não leva acento. Devolve { criado, atendimento, leitura }; criado = false quando o chamado
   * já tinha atendimento (nada foi criado).
   */
  enviarPdf: (arquivo, aoProgredir) =>
    http.post('/atendimentos', arquivo, {
      headers: { 'Content-Type': 'application/pdf', 'X-Nome-Arquivo': encodeURIComponent(arquivo.name) },
      timeout: 300000,
      onUploadProgress: (e) => aoProgredir?.(e.total ? e.loaded / e.total : null),
    }),

  apagarAtendimento: (id) => http.delete(`/atendimentos/${id}`),
}
