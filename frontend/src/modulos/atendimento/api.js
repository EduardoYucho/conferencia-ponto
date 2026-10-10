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

  /** Põe na fila o que falta (nesta versão, o download dos anexos). Devolve o progresso. */
  processar: (id) => http.post(`/atendimentos/${id}/processar`),
  cancelar: (id) => http.post(`/atendimentos/${id}/cancelar`),
  retomar: (id) => http.post(`/atendimentos/${id}/retomar`),
  tentarDeNovo: (id, arquivoId) => http.post(`/atendimentos/${id}/arquivos/${arquivoId}/tentar-de-novo`),

  /**
   * Envia uma ligação, o vídeo ou um print em fluxo, com a data de modificação do arquivo (usada na linha do tempo).
   * @param origem 'ligacao' | 'video' | 'print_extra'
   */
  enviarExtra: (id, arquivo, origem, aoProgredir) =>
    http.put(`/atendimentos/${id}/arquivos`, arquivo, {
      params: { origem, nome: arquivo.name, modificadoEm: arquivo.lastModified || undefined },
      headers: { 'Content-Type': arquivo.type || 'application/octet-stream' },
      timeout: 0,
      onUploadProgress: (e) => aoProgredir?.(e.total ? e.loaded / e.total : null),
    }),

  /** Envio à mão do arquivo de um anexo da conversa que não pôde ser baixado. */
  enviarConteudo: (id, arquivoId, arquivo, aoProgredir) =>
    http.put(`/atendimentos/${id}/arquivos/${arquivoId}/conteudo`, arquivo, {
      headers: { 'Content-Type': arquivo.type || 'application/octet-stream', 'X-Nome-Arquivo': encodeURIComponent(arquivo.name) },
      timeout: 0,
      onUploadProgress: (e) => aoProgredir?.(e.total ? e.loaded / e.total : null),
    }),

  tirarArquivo: (id, arquivoId) => http.delete(`/atendimentos/${id}/arquivos/${arquivoId}`),

  /** PDF novo do mesmo chamado, enviado na tela do atendimento: renova os links dos anexos que faltam. */
  renovarPdf: (id, arquivo) =>
    http.put(`/atendimentos/${id}/pdf`, arquivo, {
      headers: { 'Content-Type': 'application/pdf', 'X-Nome-Arquivo': encodeURIComponent(arquivo.name) },
      timeout: 300000,
    }),

  /** Administrador: { ocupado, livre, pausados } (bytes). */
  espaco: () => http.get('/atendimentos/acessos/espaco'),
}
