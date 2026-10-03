/**
 * Como a tela escreve durações: do jeito que se fala ("8h 48min", "12 min"), igual ao servidor
 * (application/tela/Horas.java). Aqui só se formata — o que é a favor, devendo ou faltando vem decidido da API.
 */

/** 31680 → "8h 48min"; 3600 → "1h"; 720 → "12 min"; 45 → "45 s"; 0 → "0 min"; null → "—". Sem sinal. */
export function duracao(segundos) {
  if (segundos === null || segundos === undefined) return '—'
  const s = Math.abs(Math.round(segundos))
  if (s === 0) return '0 min'
  if (s < 60) return `${s} s`
  const horas = Math.floor(s / 3600)
  const minutos = Math.floor((s % 3600) / 60)
  if (horas === 0) return `${minutos} min`
  return minutos === 0 ? `${horas}h` : `${horas}h ${String(minutos).padStart(2, '0')}min`
}

/** 900 → "+ 15 min"; −720 → "− 12 min"; 0 → "0 min"; null → "—". */
export function saldo(segundos) {
  if (segundos === null || segundos === undefined) return '—'
  if (segundos === 0) return duracao(0)
  return `${segundos > 0 ? '+' : '−'} ${duracao(segundos)}`
}

/** 900 → "a favor"; −720 → "devendo"; 0 → "em dia". */
export function sentido(segundos) {
  if (!segundos) return 'em dia'
  return segundos > 0 ? 'a favor' : 'devendo'
}

/** Valor exato, com segundos, para quem quiser conferir (dica do número): 569 → "+00:09:29". */
export function exato(segundos, { sinal = true } = {}) {
  if (segundos === null || segundos === undefined) return ''
  const s = Math.abs(Math.round(segundos))
  const p = (n) => String(n).padStart(2, '0')
  const texto = `${p(Math.floor(s / 3600))}:${p(Math.floor((s % 3600) / 60))}:${p(s % 60)}`
  if (!sinal || segundos === 0) return texto
  return `${segundos > 0 ? '+' : '−'}${texto}`
}

/** Classe de cor de texto para um saldo (verde a favor, vermelho devendo). */
export function corDoSaldo(segundos) {
  if (!segundos) return 'text-texto'
  return segundos > 0 ? 'text-positivo' : 'text-negativo'
}

/** Classe do selo para o tom que a API devolve (POSITIVO, NEGATIVO, ATENCAO, INFO, NEUTRO). */
export function seloDoTom(tom) {
  return {
    POSITIVO: 'selo-positivo', NEGATIVO: 'selo-negativo', ATENCAO: 'selo-atencao', INFO: 'selo-info',
  }[tom] ?? 'selo-neutro'
}

/** Classe de cor de texto para o tom que a API devolve. */
export function textoDoTom(tom) {
  return {
    POSITIVO: 'text-positivo', NEGATIVO: 'text-negativo', ATENCAO: 'text-atencao', INFO: 'text-primaria',
  }[tom] ?? 'text-texto-2'
}

/** "2026-09-28" → "28/09/2026" */
export function dataBR(iso) {
  if (!iso) return '—'
  const [a, m, d] = iso.slice(0, 10).split('-')
  return `${d}/${m}/${a}`
}

/** "2026-09-28" → "28/09" */
export function diaMes(iso) {
  if (!iso) return '—'
  const [, m, d] = iso.slice(0, 10).split('-')
  return `${d}/${m}`
}

/** 900 → "+ 15 min a favor"; −720 → "− 12 min devendo"; 0 → "em dia"; null → "—". */
export function saldoComSentido(segundos) {
  if (segundos === null || segundos === undefined) return '—'
  return segundos === 0 ? 'em dia' : `${saldo(segundos)} ${sentido(segundos)}`
}

/** "2026-10-03T13:42:10Z" → "03/10/2026 10:42" (no fuso do navegador). */
export function momento(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}
