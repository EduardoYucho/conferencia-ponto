import { http } from '@/api/http'

/** Rotas do gerador de atendimentos (todas devolvem o conteúdo de "dados"). */
export const atendimentoApi = {
  /** O que a pessoa logada pode: { gerador, espaco, iaLocal }. */
  meuAcesso: () => http.get('/atendimentos/meu-acesso'),

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

  /**
   * Põe na fila o que falta (o download dos anexos e a análise de cada arquivo) e, no fim, a redação das saídas
   * pedidas (ex.: ['resumo']) que ainda não existem. Devolve o progresso.
   */
  processar: (id, saidas) => http.post(`/atendimentos/${id}/processar`, saidas?.length ? { saidas } : undefined),
  cancelar: (id) => http.post(`/atendimentos/${id}/cancelar`),
  retomar: (id) => http.post(`/atendimentos/${id}/retomar`),
  tentarDeNovo: (id, arquivoId) => http.post(`/atendimentos/${id}/arquivos/${arquivoId}/tentar-de-novo`),
  /** O progresso de agora (o mesmo do tempo real), para conferir quando os eventos podem ter se perdido. */
  progresso: (id) => http.get(`/atendimentos/${id}/progresso`),

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

  /**
   * A análise de um arquivo: { categoria, modelo, versaoPrompt, atual, tokensEntrada, tokensSaida, resultado }; no
   * vídeo, também quadros: [{ passo, instanteMs, instante, disponivel }] e avisoDosQuadros.
   */
  analise: (id, arquivoId) => http.get(`/atendimentos/${id}/arquivos/${arquivoId}/analise`),
  /** O quadro de um passo do vídeo (Blob na resposta inteira), para a miniatura. */
  quadro: (id, arquivoId, instanteMs) =>
    http.get(`/atendimentos/${id}/arquivos/${arquivoId}/quadros/${instanteMs}`, { responseType: 'blob', timeout: 60000 }),

  /**
   * O que a pessoa informa para os chamados do desenvolvimento:
   * { versaoCliente, testadoUltimaVersao (true, false ou null), caminhoBackup, chamadoRelacionado }.
   */
  salvarCampos: (id, campos) => http.put(`/atendimentos/${id}/campos`, campos),

  /** A versão mais recente de cada texto gerado: [{ id, tipo, rotulo, versao, situacao, erro, assunto }]. */
  saidas: (id) => http.get(`/atendimentos/${id}/saidas`),

  /** Gera de novo (uma versão nova, com as análises guardadas). Devolve o progresso. */
  gerarDeNovo: (id, tipo) => http.post(`/atendimentos/${id}/saidas/${tipo}/gerar`),

  /** O material da redação: { montadaEm, itens: [{ id, tipo, momento, lado, remetente, texto, nome, ... }] }. */
  linhaDoTempo: (id) => http.get(`/atendimentos/${id}/linha-do-tempo`),

  /** Um texto gerado: assunto, HTML (com data-fontes e data-imagem), imagens, avisos e campos da base. */
  saida: (id, saidaId) => http.get(`/atendimentos/${id}/saidas/${saidaId}`),
  salvarAssunto: (id, saidaId, assunto) => http.put(`/atendimentos/${id}/saidas/${saidaId}/assunto`, { assunto }),
  /** A Tela/Funcionalidade do cabeçalho de um chamado. */
  salvarTela: (id, saidaId, telaFuncionalidade) => http.put(`/atendimentos/${id}/saidas/${saidaId}/tela`, { telaFuncionalidade }),
  /** O texto editado (o servidor limpa e renumera as imagens). */
  salvarTexto: (id, saidaId, html) => http.put(`/atendimentos/${id}/saidas/${saidaId}/texto`, { html }),
  /** As imagens que ficam no texto, na ordem (o servidor renumera). */
  ordenarImagens: (id, saidaId, incluidas) => http.put(`/atendimentos/${id}/saidas/${saidaId}/imagens`, { incluidas }),
  desfazerEdicao: (id, saidaId) => http.post(`/atendimentos/${id}/saidas/${saidaId}/desfazer`),
  /**
   * Confirma o texto e o envia para a base de conhecimento com o registro revisado. Devolve { saida, avisos }; a base
   * pode recusar (campo longo, base não liberada), e então o texto continua pronto.
   */
  confirmar: (id, saidaId, registro) => http.post(`/atendimentos/${id}/saidas/${saidaId}/confirmar`, registro),
  /** { html, texto, avisos } para a área de transferência; modo: com_imagens, so_texto ou codigo_fonte. */
  copia: (id, saidaId, modo) => http.get(`/atendimentos/${id}/saidas/${saidaId}/copia`, { params: { modo }, timeout: 60000 }),
  /** A imagem (Blob na resposta inteira), para a miniatura. */
  imagem: (id, saidaId, imagemId) =>
    http.get(`/atendimentos/${id}/saidas/${saidaId}/imagens/${imagemId}`, { responseType: 'blob', timeout: 60000 }),
  /**
   * Liga ou desliga as marcações em vermelho de uma imagem (experimental): as áreas que a análise apontou ganham uma
   * borda vermelha numa cópia, e a cópia passa a valer na cópia para o chamado, no .zip e na base. Devolve a saída.
   */
  marcarImagem: (id, saidaId, imagemId, ligada) =>
    http.put(`/atendimentos/${id}/saidas/${saidaId}/imagens/${imagemId}/marcacao`, { ligada }, { timeout: 60000 }),
  /** O .zip com Imagem_01, Imagem_02... na ordem do texto (resposta inteira, para salvar). */
  imagensZip: (id, saidaId) =>
    http.get(`/atendimentos/${id}/saidas/${saidaId}/imagens.zip`, { responseType: 'blob', timeout: 120000 }),

  /** A habilidade para criar no app do Gemini: { versao, nome, instrucoes, skillMd }. */
  habilidadeDoGemini: () => http.get('/atendimentos/app-gemini/habilidade'),
  /**
   * Monta o pacote para o Gemini: { arquivos: [{ nome, tamanho, conteudo }], avisos, audioMs, videoMs, versaoDaHabilidade }
   * (a conversa e as imagens num PDF, os áudios num MP3: pode demorar).
   */
  pacoteParaOGemini: (id) => http.post(`/atendimentos/${id}/app-gemini/pacote`, null, { timeout: 300000 }),
  /** Um arquivo do pacote (resposta inteira, para salvar). */
  arquivoParaOApp: (id, nome) =>
    http.get(`/atendimentos/${id}/app-gemini/arquivos/${encodeURIComponent(nome)}`, { responseType: 'blob', timeout: 600000 }),
  /** A resposta colada do Gemini vira o texto. Devolve o progresso (com os textos). */
  respostaDoApp: (id, tipo, resposta) => http.post(`/atendimentos/${id}/app-gemini/${tipo}/resposta`, { resposta }, { timeout: 120000 }),

  /** Com "Ver o espaço em disco dos atendimentos": { ocupado, livre, pausados } (bytes). */
  espaco: () => http.get('/atendimentos/espaco'),
}
