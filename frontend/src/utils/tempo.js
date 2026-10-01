/** Jornada base de um dia útil, em segundos (08:48:00). */
export const JORNADA_BASE_PADRAO = 31680

const MESES = [
  'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
  'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro',
]
const DIAS_SEMANA = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb']

const pad = (n) => String(n).padStart(2, '0')

/**
 * Durações em SEGUNDOS, como o relatório do RH: 32336 -> "08:58:56" (sempre positivo).
 * Com { curto: true } omite os segundos: "08:58".
 */
export function formatarDuracao(segundos, { curto = false } = {}) {
  const total = Math.abs(Math.round(segundos ?? 0))
  const h = pad(Math.floor(total / 3600))
  const m = pad(Math.floor((total % 3600) / 60))
  return curto ? `${h}:${m}` : `${h}:${m}:${pad(total % 60)}`
}

/** 569 -> "+00:09:29", -304 -> "−00:05:04", 0 -> "00:00:00", null -> "—". */
export function formatarSaldo(segundos, opcoes) {
  if (segundos === null || segundos === undefined) return '—'
  if (segundos === 0) return formatarDuracao(0, opcoes)
  return `${segundos > 0 ? '+' : '−'}${formatarDuracao(segundos, opcoes)}`
}

/** "08:05:52" -> 29152 (segundo do dia). */
export function paraSegundos(horario) {
  if (!horario) return null
  const [h, m, s = 0] = horario.split(':').map(Number)
  return h * 3600 + m * 60 + s
}

/** "08:05" ou "08:05:52" -> "08:05:52" (o campo de hora do navegador omite ":00"). */
export function comSegundos(horario) {
  if (!horario) return horario
  return horario.length === 5 ? `${horario}:00` : horario.slice(0, 8)
}

export function segundosAgora(agora = new Date()) {
  return agora.getHours() * 3600 + agora.getMinutes() * 60 + agora.getSeconds()
}

/** "08:02:31" -> "08:02" ; null -> "--:--". */
export function hhmm(horario) {
  return horario ? horario.slice(0, 5) : '--:--'
}

/** "08:02:31" -> 482 (minuto do dia, segundos descartados). */
export function paraMinutos(horario) {
  if (!horario) return null
  const [h, m] = horario.split(':').map(Number)
  return h * 60 + m
}

/** Data local no formato ISO (yyyy-MM-dd), sem conversão para UTC. */
export function dataISO(data = new Date()) {
  return `${data.getFullYear()}-${pad(data.getMonth() + 1)}-${pad(data.getDate())}`
}

/** "2026-09-28" -> Date local (meio-dia, imune a horário de verão). */
export function deISO(iso) {
  const [a, m, d] = iso.split('-').map(Number)
  return new Date(a, m - 1, d, 12)
}

export function nomeMes(mes) {
  return MESES[mes - 1]
}

export function diaSemanaCurto(iso) {
  return DIAS_SEMANA[deISO(iso).getDay()]
}

export function ehFimDeSemana(iso) {
  const dia = deISO(iso).getDay()
  return dia === 0 || dia === 6
}

/** "2026-09-28" -> "28/09/2026" */
export function dataBR(iso) {
  if (!iso) return '—'
  const [a, m, d] = iso.slice(0, 10).split('-')
  return `${d}/${m}/${a}`
}

/** "2026-09-28" -> "28/09" */
export function dataCurta(iso) {
  const [, m, d] = iso.split('-')
  return `${d}/${m}`
}

/** Lista de datas ISO de todos os dias do mês. */
export function diasDoMes(ano, mes) {
  const total = new Date(ano, mes, 0).getDate()
  return Array.from({ length: total }, (_, i) => `${ano}-${pad(mes)}-${pad(i + 1)}`)
}

export function minutosAgora(agora = new Date()) {
  return agora.getHours() * 60 + agora.getMinutes()
}

/**
 * Duração digitada pelo usuário -> "HH:MM" (ou null se inválida).
 * Aceita "4", "4:30", "04:30", "40:00", "4h", "4h30" e "4,5" (= 04:30).
 */
export function normalizarDuracao(texto) {
  const t = String(texto ?? '').trim().toLowerCase().replace(/\s+/g, '')
  if (!t) return null
  let horas
  let minutos
  let m
  if ((m = t.match(/^(\d{1,3})(?::|h)(\d{1,2})?(?:min|m)?$/))) {
    horas = Number(m[1])
    minutos = Number(m[2] ?? 0)
  } else if ((m = t.match(/^(\d{1,3})(?:[.,](\d{1,2}))?$/))) {
    horas = Number(m[1])
    minutos = m[2] ? Math.round(Number(`0.${m[2]}`) * 60) : 0
  } else {
    return null
  }
  if (minutos > 59 || (horas === 0 && minutos === 0)) return null
  return `${pad(horas)}:${pad(minutos)}`
}

/** "04:30" -> 16200 */
export function duracaoEmSegundos(hhmmTexto) {
  if (!hhmmTexto) return 0
  const [h, m, s = 0] = hhmmTexto.split(':').map(Number)
  return h * 3600 + m * 60 + s
}

// ------------------------------------------------------------ horário de trabalho

/** Dias da semana do horário (chaves usadas pela API), de segunda a domingo. */
export const DIAS_HORARIO = [
  { sigla: 'SEG', nome: 'Segunda' },
  { sigla: 'TER', nome: 'Terça' },
  { sigla: 'QUA', nome: 'Quarta' },
  { sigla: 'QUI', nome: 'Quinta' },
  { sigla: 'SEX', nome: 'Sexta' },
  { sigla: 'SAB', nome: 'Sábado' },
  { sigla: 'DOM', nome: 'Domingo' },
]

const PERIODO = /^(\d{1,2}):(\d{2})\s*[-–]\s*(\d{1,2}):(\d{2})$/

/**
 * "08:00-12:00 13:00-17:48" -> [{ entrada: '08:00', saida: '12:00' }, ...]; vazio -> [].
 * Lança Error com a mensagem para o usuário quando o texto é inválido.
 */
export function periodosDoTexto(texto) {
  const t = String(texto ?? '').trim()
  if (!t) return []
  const partes = t.split(/[\s,;]+(?=\d)/).map((p) => p.trim()).filter(Boolean)
  const periodos = partes.map((p) => {
    const m = p.match(PERIODO)
    if (!m) throw new Error(`"${p}" não é um período (use 08:00-12:00).`)
    const [h1, m1, h2, m2] = m.slice(1).map(Number)
    if (h1 > 23 || h2 > 23 || m1 > 59 || m2 > 59) throw new Error(`Horário inválido em "${p}".`)
    return { entrada: `${pad(h1)}:${pad(m1)}`, saida: `${pad(h2)}:${pad(m2)}` }
  })
  if (periodos.length > 3) throw new Error('No máximo 3 períodos por dia.')
  let anterior = null
  for (const p of periodos) {
    if (p.saida <= p.entrada) throw new Error(`Em ${p.entrada}-${p.saida} a saída deve ser depois da entrada.`)
    if (anterior && p.entrada < anterior) throw new Error('Os períodos precisam estar em ordem e sem sobreposição.')
    anterior = p.saida
  }
  return periodos
}

/** [{ entrada, saida }] -> "08:00-12:00 13:00-17:48" */
export function textoDosPeriodos(periodos) {
  return (periodos ?? []).map((p) => `${hhmm(p.entrada)}-${hhmm(p.saida)}`).join(' ')
}

/** Carga (segundos) de uma lista de períodos. */
export function cargaDosPeriodos(periodos) {
  return (periodos ?? []).reduce((soma, p) => soma + (paraSegundos(comSegundos(hhmm(p.saida))) - paraSegundos(comSegundos(hhmm(p.entrada)))), 0)
}

/** Carga de um texto de períodos (texto inválido = null). */
export function cargaDoTexto(texto) {
  try {
    return cargaDosPeriodos(periodosDoTexto(texto))
  } catch {
    return null
  }
}

/** Carga mais comum entre os dias com expediente do horário (o "dia inteiro" de quem compensa uma folga). */
export function cargaTipica(dias) {
  const contagem = new Map()
  for (const texto of Object.values(dias ?? {})) {
    const carga = cargaDoTexto(texto)
    if (carga) contagem.set(carga, (contagem.get(carga) ?? 0) + 1)
  }
  let melhor = null
  for (const [carga, n] of contagem) if (!melhor || n > melhor.n || (n === melhor.n && carga > melhor.carga)) melhor = { carga, n }
  return melhor?.carga ?? JORNADA_BASE_PADRAO
}
