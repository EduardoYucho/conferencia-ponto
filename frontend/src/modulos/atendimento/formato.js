/** Textos e datas das telas do gerador de atendimentos (no fuso e no formato do navegador da pessoa). */

const DATA_HORA = { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }
const DIA_MES_HORA = { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }

export function dataHora(iso) {
  return iso ? new Date(iso).toLocaleString('pt-BR', DATA_HORA) : '—'
}

export function hora(iso) {
  return iso ? new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : ''
}

function mesmoDia(a, b) {
  return a.toDateString() === b.toDateString()
}

/** "09/10/2026, 08:00 – 09:30" (ou as duas datas inteiras quando o chamado passou de um dia para outro). */
export function periodo(inicio, fim) {
  if (!inicio) return '—'
  if (!fim) return `${dataHora(inicio)} (sem término)`
  const a = new Date(inicio)
  const b = new Date(fim)
  return mesmoDia(a, b) ? `${dataHora(inicio)} – ${hora(fim)}` : `${dataHora(inicio)} – ${dataHora(fim)}`
}

/** Até quando os links dos anexos valem (o Digisac assina por 24 h a partir da exportação). */
export function validadeDosLinks(validoAte, vencidos) {
  if (!validoAte) return 'O PDF não informa a validade dos links'
  const quando = new Date(validoAte).toLocaleString('pt-BR', DIA_MES_HORA).replace(', ', ' às ')
  return vencidos ? `Links vencidos em ${quando}` : `Links válidos até ${quando}`
}

export const CATEGORIAS = {
  imagem: 'Imagem',
  audio: 'Áudio',
  video: 'Vídeo',
  documento: 'Documento',
  outro: 'Outro',
}

/** "2 imagens, 3 áudios" a partir de { imagem: 2, audio: 3 }. */
export function anexosPorCategoria(porCategoria) {
  const plurais = { imagem: 'imagens', audio: 'áudios', video: 'vídeos', documento: 'documentos', outro: 'outros' }
  const partes = Object.entries(porCategoria ?? {})
    .filter(([, n]) => n > 0)
    .map(([c, n]) => `${n} ${n === 1 ? (CATEGORIAS[c] ?? c).toLowerCase() : plurais[c] ?? c}`)
  return partes.length ? partes.join(', ') : 'nenhum'
}

export const SITUACOES = {
  novo: { rotulo: 'Novo', classe: 'border-linha text-tinta-suave' },
  processando: { rotulo: 'Processando', classe: 'border-tinta/40 text-tinta' },
  pausado: { rotulo: 'Pausado', classe: 'border-carimbo/40 bg-carimbo/5 text-carimbo' },
  pronto: { rotulo: 'Pronto', classe: 'border-credito/40 bg-credito/5 text-credito' },
  com_falhas: { rotulo: 'Com falhas', classe: 'border-carimbo/40 bg-carimbo/5 text-carimbo' },
  cancelado: { rotulo: 'Cancelado', classe: 'border-linha text-tinta-apagada' },
}

export const SITUACOES_DO_ANEXO = {
  aguardando: { rotulo: 'aguardando download', classe: 'text-tinta-suave' },
  baixando: { rotulo: 'baixando…', classe: 'text-tinta' },
  pronto: { rotulo: 'baixado', classe: 'text-credito' },
  falhou: { rotulo: 'falhou', classe: 'font-semibold text-carimbo' },
  vencido: { rotulo: 'link vencido', classe: 'font-semibold text-carimbo' },
  nao_suportado: { rotulo: 'não suportado', classe: 'font-semibold text-carimbo' },
  removido: { rotulo: 'tirado', classe: 'text-tinta-apagada' },
}

/** Quantos trechos saíram da conversa ("2 trechos omitidos: chave do bot e acesso remoto"). */
export function textoDosOmitidos(omitidos) {
  const chave = omitidos?.chaveDoBot ?? 0
  const acesso = omitidos?.acessoRemoto ?? 0
  if (!chave && !acesso) return 'Nenhum trecho omitido (não havia chave do bot nem dados de acesso remoto).'
  const partes = []
  if (chave) partes.push(`${chave} da chave do bot`)
  if (acesso) partes.push(`${acesso} de acesso remoto (ID ou senha)`)
  const total = chave + acesso
  return `${total} ${total === 1 ? 'trecho omitido' : 'trechos omitidos'}: ${partes.join(' e ')}. Aparecem como [omitido].`
}

/** "1 mensagem", "3 eventos". */
export function contagem(n, singular, plural) {
  return `${n} ${n === 1 ? singular : plural}`
}

/** "1,2 MB" a partir dos bytes. */
export function tamanho(bytes) {
  if (bytes == null || bytes < 0) return ''
  if (bytes < 1024) return `${bytes} B`
  const unidades = ['KB', 'MB', 'GB', 'TB']
  let valor = bytes / 1024
  let i = 0
  while (valor >= 1024 && i < unidades.length - 1) {
    valor /= 1024
    i++
  }
  return `${valor.toLocaleString('pt-BR', { maximumFractionDigits: valor < 10 ? 1 : 0 })} ${unidades[i]}`
}

/** De onde veio o arquivo enviado pela pessoa. */
export const ORIGENS = {
  ligacao: 'Ligação',
  video: 'Vídeo de reprodução',
  print_extra: 'Print',
}

/** O que se aceita no envio (as extensões que o Gemini lê). */
export const ACEITOS_NO_ENVIO =
  '.png,.jpg,.jpeg,.webp,.heic,.heif,.mp3,.m4a,.wav,.ogg,.oga,.opus,.aac,.flac,.mp4,.mov,.webm,.avi,.mkv,.pdf,.txt'
