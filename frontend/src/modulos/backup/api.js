import { http } from '@/api/http'

/** Rotas dos backups de clientes (as de JSON devolvem o conteúdo de "dados"). */
export const backupApi = {
  /** { tunel: { modo, endereco, mensagem, desde }, nivelZstd, validadeLinkHoras, podeApagar } */
  estado: () => http.get('/backup/estado'),
  reiniciarTunel: () => http.post('/backup/tunel/reiniciar'),

  clientes: () => http.get('/backup/clientes'),
  criarCliente: (cliente) => http.post('/backup/clientes', cliente),
  alterarCliente: (nome, cliente) => http.put(`/backup/clientes/${encodeURIComponent(nome)}`, cliente),
  removerCliente: (nome) => http.delete(`/backup/clientes/${encodeURIComponent(nome)}`),

  /** O histórico, a mais recente primeiro (andamento, conferência; `minha` = é este login que recebe). */
  coletas: () => http.get('/backup/coletas'),
  coleta: (id) => http.get(`/backup/coletas/${id}`),
  criarColeta: (cliente) => http.post('/backup/coletas', { cliente }),
  cancelar: (id) => http.post(`/backup/coletas/${id}/cancelar`),
  /** Tira do histórico (o arquivo no computador de quem recebeu fica). */
  apagar: (id) => http.delete(`/backup/coletas/${id}`),

  // ---- o navegador de quem recebe
  /**
   * O próximo pedaço para gravar (espera até 25 s no servidor). Resposta Axios: status 204 = nada ainda; 200 com
   * `data` (ArrayBuffer) e os cabeçalhos x-seq e x-sha256.
   */
  receber: (id) => http.get(`/backup/coletas/${id}/receber`, { responseType: 'arraybuffer', timeout: 60000 }),
  /** O pedaço {seq} foi gravado (com este SHA-256): libera a página do cliente. */
  recebido: (id, seq, sha256) => http.post(`/backup/coletas/${id}/recebido`, { seq, sha256 }),
  /** O último pedaço (manifesto e fim do .tar), depois que todos foram gravados. Resposta Axios com ArrayBuffer. */
  parteFinal: (id) => http.get(`/backup/coletas/${id}/final`, { responseType: 'arraybuffer', timeout: 60000 }),
  /** O resultado da conferência feita aqui. */
  resultado: (id, resultado) => http.post(`/backup/coletas/${id}/resultado`, resultado),
}
