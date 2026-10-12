/**
 * Cópia para a área de transferência, nos dois jeitos que o navegador permite:
 *
 * - direto: com conexão segura (https ou localhost), o navegador grava o HTML formatado e o texto. O conteúdo é pedido
 *   ao servidor dentro do próprio clique (o ClipboardItem aceita uma promessa), para o navegador não recusar a cópia
 *   por ela ter vindo "depois" do clique;
 * - alternativo: fora disso (ex.: http://<ip>:8080), o conteúdo é selecionado numa área fora da tela e copiado com o
 *   comando de cópia do próprio navegador, que precisa ser chamado direto num clique (por isso a tela pede um segundo
 *   clique, em "Copiar agora").
 */

/** O navegador permite gravar HTML na área de transferência direto. */
export function copiaDiretaDisponivel() {
  return !!(window.isSecureContext && navigator.clipboard?.write && typeof window.ClipboardItem === 'function')
}

/** @param conteudo Promise de { html, texto } (pedida no clique) */
export function copiarHtmlDireto(conteudo) {
  const item = new window.ClipboardItem({
    'text/html': conteudo.then((c) => new Blob([c.html], { type: 'text/html' })),
    'text/plain': conteudo.then((c) => new Blob([c.texto ?? ''], { type: 'text/plain' })),
  })
  return navigator.clipboard.write([item])
}

/** @param texto Promise do texto (pedido no clique) */
export function copiarTextoDireto(texto) {
  const item = new window.ClipboardItem({ 'text/plain': texto.then((t) => new Blob([t], { type: 'text/plain' })) })
  return navigator.clipboard.write([item])
}

/**
 * O modo alternativo: chamado direto no clique. Copia o HTML formatado (como = 'html') ou o texto (como = 'texto').
 *
 * @return true se o navegador copiou
 */
export function copiarPelaSelecao(conteudo, como) {
  const html = como === 'html'
  const area = document.createElement(html ? 'div' : 'textarea')
  area.setAttribute('aria-hidden', 'true')
  Object.assign(area.style, { position: 'fixed', left: '-10000px', top: '0', width: '800px', whiteSpace: 'pre-wrap' })
  if (html) {
    area.contentEditable = 'true'
    area.innerHTML = conteudo
  } else {
    area.value = conteudo
  }
  document.body.appendChild(area)
  const selecao = window.getSelection()
  let copiou = false
  try {
    if (html) {
      const faixa = document.createRange()
      faixa.selectNodeContents(area)
      selecao.removeAllRanges()
      selecao.addRange(faixa)
    } else {
      area.select()
    }
    copiou = document.execCommand('copy')
  } catch {
    copiou = false
  } finally {
    selecao?.removeAllRanges()
    area.remove()
  }
  return copiou
}
