import { http } from './http'

/** Endpoints REST do back-end (todos retornam o conteúdo de "dados"). */
export const pontoApi = {
  configuracao: () => http.get('/configuracao'),

  // ------------------------------------------------ telas prontas (o servidor decide; a tela só mostra)
  /** Início: o dia de hoje, pendências, saldos e últimos dias. */
  inicio: () => http.get('/inicio'),
  /** Meu ponto: o mês dia a dia, com situação, frase e ações de cada dia. Sem ano/mês: o mês de hoje. */
  meuPonto: (ano, mes) => http.get('/ponto', { params: { ano, mes } }),
  /** Banco de horas: saldo do ciclo, meses, horas usadas ou somadas à mão e fechamentos anteriores. */
  banco: () => http.get('/banco'),

  mes: (ano, mes) => http.get('/jornadas', { params: { ano, mes } }),

  dia: (data) => http.get(`/jornadas/${data}`),

  saldos: (ano, mes) => http.get('/saldos', { params: { ano, mes } }),

  /** Sem data/horário o servidor usa o próprio relógio. */
  registrarBatida: ({ data, horario } = {}) => http.post('/jornadas/batidas', { data, horario }),

  /** @param {{ data: string, intervalos: { entrada: string, saida: string }[] }} payload */
  lancarManual: (payload) => http.post('/jornadas/manual', payload),

  excluir: (data) => http.delete(`/jornadas/${data}`),

  /**
   * Ajuste manual (correção do RH): lista FINAL de batidas do dia + justificativa.
   * @param {string} data yyyy-MM-dd
   * @param {{ horarios: string[], justificativa: string }} ajuste
   */
  ajustarBatidas: (data, ajuste) => http.put(`/jornadas/${data}/batidas`, ajuste),

  /** Batidas com PDF (travadas no ajuste) + histórico de ajustes do dia. */
  contextoAjuste: (data) => http.get(`/jornadas/${data}/ajustes`),

  /** Grade da coordenação: dias do mês com batidas, tolerância, saldo e comprovantes. */
  auditoria: (ano, mes) => http.get('/auditoria', { params: { ano, mes } }),

  /** PDF arquivado (resposta completa: blob + headers). */
  baixarComprovante: (id) => http.get(`/comprovantes/${id}/download`, { responseType: 'blob', timeout: 60000 }),

  /** Varre de novo a pasta monitorada do usuário. */
  reprocessarPasta: () => http.post('/importacoes/reprocessar'),

  /**
   * Comprovantes PDF enviados pela tela (mesmas regras da pasta monitorada).
   * @returns {Promise<{ nomeArquivo, status: 'IMPORTADO'|'DUPLICADO'|'REJEITADO'|'INVALIDO'|'JA_PROCESSADO', mensagem }[]>}
   */
  enviarComprovantes: (arquivos) => {
    const formulario = new FormData()
    for (const arquivo of arquivos) formulario.append('arquivos', arquivo)
    return http.post('/importacoes/enviar', formulario, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },

  /** Estado do monitor de PDFs + últimos comprovantes processados. */
  importacoes: (limite = 20) => http.get('/importacoes', { params: { limite } }),

  // ------------------------------------------------ banco de horas (ciclo)
  /** Ciclo aberto: saldo desde o início, previsão de fechamento, meses do ciclo. */
  cicloAtual: () => http.get('/ciclos/atual'),

  ciclos: () => http.get('/ciclos'),

  /** Corrige o início (e a previsão) do ciclo aberto. */
  corrigirCiclo: ({ dataInicio, dataFimPrevista = null }) => http.put('/ciclos/atual', { dataInicio, dataFimPrevista }),

  /** Congela o saldo até ultimoDia e recomeça a contagem do zero no dia seguinte. */
  fecharCiclo: ({ ultimoDia, observacao }) => http.post('/ciclos/fechar', { ultimoDia, observacao }),

  desfazerFechamento: () => http.post('/ciclos/desfazer-fechamento'),

  // ------------------------------------------------------- notificações
  notificacoes: () => http.get('/notificacoes'),
  marcarNotificacaoLida: (id) => http.post(`/notificacoes/${id}/lida`),
  marcarNotificacoesLidas: () => http.post('/notificacoes/lidas'),

  // ------------------------------------------- férias, atestados, folgas, abonos
  ausencias: (inicio, fim) => http.get('/ausencias', { params: { inicio, fim } }),
  cadastrarAusencia: (ausencia) => http.post('/ausencias', ausencia),
  excluirAusencia: (id) => http.delete(`/ausencias/${id}`),

  // ------------------------------------------------------------ feriados
  feriados: (inicio, fim) => http.get('/feriados', { params: { inicio, fim } }),
  /** @param {{ data: string, descricao: string, abrangencia?: 'NACIONAL'|'ESTADUAL'|'MUNICIPAL'|'EMPRESA' }} feriado */
  cadastrarFeriado: (feriado) => http.post('/feriados', feriado),
  excluirFeriado: (data) => http.delete(`/feriados/${data}`),

  // ------------------------------------- lançamentos avulsos no banco de horas
  lancamentosBanco: (inicio, fim) => http.get('/lancamentos-banco', { params: { inicio, fim } }),
  /** @param {{ data: string, duracao: string, sentido: 'DEBITO'|'CREDITO', descricao: string }} lancamento */
  lancarNoBanco: (lancamento) => http.post('/lancamentos-banco', lancamento),
  excluirLancamentoBanco: (id) => http.delete(`/lancamentos-banco/${id}`),

  // ------------------------------------------------ horário de trabalho
  /** Vigências do horário (da mais antiga para a mais recente). */
  horarios: () => http.get('/horarios'),
  /**
   * Novo horário a partir de uma data. `usuario` (login): o admin alterando o horário de outra pessoa.
   * @param {{ vigenteDesde: string, toleranciaMinutos: number, dias: Record<string, string> }} horario
   */
  salvarHorario: (horario, usuario = null) => http.post('/horarios', horario, { params: usuario ? { usuario } : {} }),
  excluirHorario: (id, usuario = null) => http.delete(`/horarios/${id}`, { params: usuario ? { usuario } : {} }),

  // ------------------------------------------------ minha conta e usuários
  salvarMinhaPasta: (pasta) => http.put('/conta/pasta', { pasta }, { timeout: 30000 }),
  verificarPasta: (pasta) => http.post('/conta/pasta/verificar', { pasta }, { timeout: 30000 }),
  titulares: () => http.get('/usuarios/titulares'),
  usuarios: () => http.get('/usuarios'),
  /** @param {{ login, nome, perfil: 'ROLE_ADMIN'|'ROLE_USER'|'ROLE_VIEWER', senhaProvisoria, pastaComprovantes? }} usuario */
  criarUsuario: (usuario) => http.post('/usuarios', usuario),
  atualizarUsuario: (id, { nome, perfil, ativo }) => http.put(`/usuarios/${id}`, { nome, perfil, ativo }),
  redefinirSenha: (id, senhaProvisoria) => http.put(`/usuarios/${id}/senha`, { senhaProvisoria }),
  definirPastaDe: (id, pasta) => http.put(`/usuarios/${id}/pasta`, { pasta }, { timeout: 30000 }),

  // ------------------------------------------- planilha de conferência
  /** Arquivo Excel com todos os meses e o resumo (resposta completa: blob + headers). */
  exportarPlanilha: () => http.get('/planilha/exportar', { responseType: 'blob', timeout: 120000 }),
  /** Planilha do Google da pessoa: { situacao, emailServico, url, titulo, sincronizadaEm, erro }. */
  planilha: () => http.get('/planilha'),
  /** `usuario` (login): o admin alterando a planilha de outra pessoa. Grava a planilha inteira antes de responder. */
  vincularPlanilha: (link, usuario = null) =>
    http.put('/planilha', { link }, { params: usuario ? { usuario } : {}, timeout: 180000 }),
  sincronizarPlanilha: (usuario = null) =>
    http.post('/planilha/sincronizar', null, { params: usuario ? { usuario } : {}, timeout: 180000 }),
  desvincularPlanilha: (usuario = null) => http.delete('/planilha', { params: usuario ? { usuario } : {} }),
  /** Conta de serviço do Google (administrador): { configurada, email, projeto, planilhas }. */
  integracaoGoogle: () => http.get('/integracoes/google'),
  /** @param {string} chave conteúdo do arquivo .json da chave */
  configurarGoogle: (chave) => http.put('/integracoes/google', { chave }, { timeout: 60000 }),
  removerGoogle: () => http.delete('/integracoes/google'),

  // ------------------------------------------- conciliação com o RH
  /** Envia o PDF do relatório de banco de horas (processado em segundo plano). */
  enviarRelatorioRh: (arquivo) => {
    const formulario = new FormData()
    formulario.append('arquivo', arquivo)
    return http.post('/conciliacoes', formulario, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 })
  },
  resumoConciliacao: () => http.get('/conciliacoes/resumo'),
  /** @param {'PENDENTE'|'ACEITO_RH'|'MANTIDO_LOCAL'|'RESOLVIDA'|'TODAS'} status */
  divergencias: (status = 'PENDENTE') => http.get('/conciliacoes/divergencias', { params: { status } }),
  aceitarDivergencia: (id) => http.post(`/conciliacoes/divergencias/${id}/aceitar`),
  manterDivergencia: (id, observacao) => http.post(`/conciliacoes/divergencias/${id}/manter`, { observacao }),
  reabrirDivergencia: (id) => http.post(`/conciliacoes/divergencias/${id}/reabrir`),
  aceitarEmLote: (tipos) => http.post('/conciliacoes/divergencias/aceitar-lote', { tipos }, { timeout: 180000 }),
  reconferir: () => http.post('/conciliacoes/reconferir', null, { timeout: 120000 }),
  excluirRelatorioRh: (id) => http.delete(`/conciliacoes/relatorios/${id}`),

  // ---------------------------------------------------------------- equipe (quem está trabalhando agora)

  /** Todos os usuários ativos com a situação de agora: { hoje, agora, total, online, emIntervalo, offline, pessoas }. */
  presenca: () => http.get('/presenca'),

  // ---------------------------------------------------------------- logs (administrador)

  /**
   * Linhas de log depois da sequência `depois` (0 = as mais recentes).
   * @param {{ depois?: number, nivel?: string, login?: string, texto?: string, limite?: number }} filtro
   */
  logsAoVivo: (filtro = {}) => http.get('/logs/ao-vivo', { params: filtro }),

  /** Arquivos de log guardados: { pasta, diasGuardados, usuarios: [{ login, dias: [{ data, horas: [{ hora, bytes }] }] }] }. */
  arquivosDeLog: () => http.get('/logs/arquivos'),

  /** O log de uma pessoa (ou do "sistema") numa hora: { login, data, hora, bytes, cortado, texto }. */
  arquivoDeLog: (login, data, hora) => http.get('/logs/arquivo', { params: { login, data, hora }, timeout: 30000 }),
}
