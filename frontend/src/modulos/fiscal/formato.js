/** Textos e cores do monitor fiscal. */

/** As situações, da pior para a melhor (a ordem da legenda e do filtro). */
export const SITUACOES = [
  { codigo: 'FORA', rotulo: 'Fora do ar', curto: 'Fora', classe: 'bg-carimbo text-cartao', borda: 'border-carimbo', texto: 'text-carimbo' },
  { codigo: 'INSTAVEL', rotulo: 'Instável ou lento', curto: 'Instável', classe: 'bg-alerta text-tinta', borda: 'border-alerta', texto: 'text-tinta' },
  { codigo: 'NORMAL', rotulo: 'No ar', curto: 'No ar', classe: 'bg-credito text-cartao', borda: 'border-credito', texto: 'text-credito' },
  { codigo: 'SEM_INFORMACAO', rotulo: 'Sem informação', curto: 'Sem info.', classe: 'hachurado bg-papel-escuro text-tinta-suave', borda: 'border-linha', texto: 'text-tinta-suave' },
]

const POR_CODIGO = Object.fromEntries(SITUACOES.map((s) => [s.codigo, s]))
export const situacao = (codigo) => POR_CODIGO[codigo] ?? POR_CODIGO.SEM_INFORMACAO
export const ruim = (codigo) => codigo === 'FORA' || codigo === 'INSTAVEL'

export const ROTULOS_DOS_DOCUMENTOS = { NFE: 'NF-e', NFCE: 'NFC-e', CTE: 'CT-e', MDFE: 'MDF-e' }

export const NOMES_DOS_ESTADOS = {
  AC: 'Acre', AL: 'Alagoas', AM: 'Amazonas', AP: 'Amapá', BA: 'Bahia', CE: 'Ceará', DF: 'Distrito Federal',
  ES: 'Espírito Santo', GO: 'Goiás', MA: 'Maranhão', MG: 'Minas Gerais', MS: 'Mato Grosso do Sul',
  MT: 'Mato Grosso', PA: 'Pará', PB: 'Paraíba', PE: 'Pernambuco', PI: 'Piauí', PR: 'Paraná', RJ: 'Rio de Janeiro',
  RN: 'Rio Grande do Norte', RO: 'Rondônia', RR: 'Roraima', RS: 'Rio Grande do Sul', SC: 'Santa Catarina',
  SE: 'Sergipe', SP: 'São Paulo', TO: 'Tocantins',
}

/** Os períodos de incidentes e disponibilidade. */
export const PERIODOS = [
  { dias: 1, rotulo: '24 horas' },
  { dias: 7, rotulo: '7 dias' },
  { dias: 30, rotulo: '30 dias' },
]

const pad = (n) => String(n).padStart(2, '0')

/** "16:51" (hoje) ou "08/11 16:51". */
export function hora(iso, agora = new Date()) {
  if (!iso) return '—'
  const d = new Date(iso)
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  return d.toDateString() === agora.toDateString() ? hm : `${pad(d.getDate())}/${pad(d.getMonth() + 1)} ${hm}`
}

/** "09/11/2026 16:51". */
export function dataHora(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 0 → "menos de 1 min", 75 → "1 h 15 min", 1500 → "1 dia e 1 h". */
export function duracao(minutos) {
  const m = Math.max(0, Math.round(minutos ?? 0))
  if (m < 1) return 'menos de 1 min'
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  if (h < 24) return m % 60 ? `${h} h ${m % 60} min` : `${h} h`
  const dias = Math.floor(h / 24)
  return `${dias} ${dias === 1 ? 'dia' : 'dias'}${h % 24 ? ` e ${h % 24} h` : ''}`
}

/** "há 3 min" (ou "agora"). */
export function haQuanto(iso, agora = new Date()) {
  if (!iso) return '—'
  const minutos = (agora.getTime() - new Date(iso).getTime()) / 60000
  return minutos < 1 ? 'agora' : `há ${duracao(minutos)}`
}

/** 99.4 → "99,4%". */
export function porcentagem(valor) {
  if (valor === null || valor === undefined) return '—'
  return `${String(valor).replace('.', ',')}%`
}

/** 1830 → "1,8 s"; 420 → "420 ms". */
export function tempo(ms) {
  if (ms === null || ms === undefined) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(1).replace('.', ',')} s` : `${ms} ms`
}

/** A lista de estados em texto: "SC, ES e mais 14". */
export function estadosEmTexto(ufs, maximo = 6) {
  if (!ufs?.length) return '—'
  if (ufs.length <= maximo) return ufs.join(', ')
  return `${ufs.slice(0, maximo).join(', ')} e mais ${ufs.length - maximo}`
}
