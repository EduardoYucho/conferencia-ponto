import { http } from '@/api/http'

/** Rotas da base de conhecimento (todas devolvem o conteúdo de "dados"). */
export const conhecimentoApi = {
  /** O que a pessoa logada pode: { pesquisar, curar, administrador }. */
  meuAcesso: () => http.get('/conhecimento/meu-acesso'),

  /** Administrador: todos os usuários ativos com o acesso de cada um à base. */
  acessos: () => http.get('/conhecimento/acessos'),

  /** Administrador: o que a pessoa pode fazer na base (curar inclui pesquisar). */
  definirAcesso: (usuarioId, pesquisar, curar) => http.put(`/conhecimento/acessos/${usuarioId}`, { pesquisar, curar }),

  /**
   * A pesquisa: { registros, total, pagina, paginas, tamanho, comTexto }. Os filtros vazios não vão.
   * @param filtros { q, tipo, situacao, cliente, modulo, autor, de, ate, validadas, pagina }
   */
  pesquisar: (filtros) => http.get('/conhecimento/registros', { params: semVazios(filtros) }),

  /** Quem já escreveu registros (o filtro por autor): [{ id, nome }]. */
  autores: () => http.get('/conhecimento/autores'),

  /** O registro com as imagens, o histórico da situação e se a pessoa pode mudar. */
  registro: (id) => http.get(`/conhecimento/registros/${id}`),

  /** O registro criado quando um texto do gerador foi confirmado: { id }. */
  daSaida: (saidaId) => http.get(`/conhecimento/registros/da-saida/${saidaId}`),

  /** Edita os campos (com a versão que a pessoa viu: se outra pessoa mudou antes, volta 409). */
  editar: (id, versao, campos) => http.put(`/conhecimento/registros/${id}`, { versao, ...campos }),

  mudarSituacao: (id, versao, situacao, chamadoDev, versaoCorrecao) =>
    http.put(`/conhecimento/registros/${id}/situacao`, { versao, situacao, chamadoDev, versaoCorrecao }),

  validar: (id, versao, validada) => http.post(`/conhecimento/registros/${id}/validar-solucao`, { versao, validada }),

  apagar: (id) => http.delete(`/conhecimento/registros/${id}`),

  /** A imagem N do registro (blob). */
  imagem: (id, numero) => http.get(`/conhecimento/registros/${id}/imagens/${numero}`, { responseType: 'blob', timeout: 60000 }),
}

function semVazios(filtros) {
  return Object.fromEntries(Object.entries(filtros ?? {}).filter(([, v]) => v !== '' && v !== null && v !== undefined && v !== false))
}
