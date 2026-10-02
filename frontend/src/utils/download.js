/** Nome do arquivo a partir do Content-Disposition (prioriza filename* UTF-8, RFC 5987). */
export function nomeDoContentDisposition(cabecalho, padrao = 'arquivo') {
  if (!cabecalho) return padrao
  const utf8 = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(cabecalho)
  if (utf8) return decodeURIComponent(utf8[1].trim().replace(/^"|"$/g, ''))
  const simples = /filename\s*=\s*"?([^";]+)"?/i.exec(cabecalho)
  return simples ? simples[1].trim() : padrao
}

/** Dispara o download de um Blob no navegador. */
export function salvarBlob(blob, nome) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = nome
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/** Download de uma resposta Axios com responseType 'blob'. */
export function salvarResposta(resposta, nomePadrao) {
  salvarBlob(resposta.data, nomeDoContentDisposition(resposta.headers['content-disposition'], nomePadrao))
}
