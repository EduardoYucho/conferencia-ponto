import { http } from '@/api/http'

/** Rotas do monitor fiscal (todas devolvem o conteúdo de "dados"). */
export const fiscalApi = {
  /** O que a pessoa logada pode: { ver, configurar }. */
  meuAcesso: () => http.get('/fiscal/meu-acesso'),

  /**
   * O quadro: { agora, coletaAtiva, intervaloMinutos, ultimaRodada, documentos[{ codigo, rotulo }],
   * estados[{ uf, celulas[{ documento, autorizador, nome, situacao, desde, detalhe, lidaEm }], contingencia }],
   * autorizadores[{ documento, autorizador, nome, contingencia, situacao, desde, detalhe, estados, servicos, incidente }],
   * contagem, fontes, avisos }.
   */
  painel: () => http.get('/fiscal/painel'),

  /** Os incidentes dos últimos `dias` (1 a 90), o mais recente primeiro. */
  incidentes: (dias) => http.get('/fiscal/incidentes', { params: { dias } }),

  /** A porcentagem no ar de cada autorizador nos últimos `dias`. */
  disponibilidade: (dias) => http.get('/fiscal/disponibilidade', { params: { dias } }),

  /** Uma leitura agora (quem pode configurar; no máximo uma por minuto). Pode levar até uns 45 segundos. */
  coletar: () => http.post('/fiscal/coletar', null, { timeout: 90000 }),
}
