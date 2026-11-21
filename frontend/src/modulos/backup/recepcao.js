import { defineStore } from 'pinia'
import { reactive } from 'vue'
import { backupApi } from '@/modulos/backup/api'
import { mensagemDe } from '@/utils/erros'
import { pastaDaColeta, permissao, pedirPermissao, escolherPasta } from '@/modulos/backup/destino'
import { importarChave, decifrar } from '@/modulos/backup/cifra'

/** Pedaço que não decifra: adulterado no caminho ou link com outra chave. Para tudo e não confirma nada. */
class PedacoAdulterado extends Error {}

/**
 * O recebimento neste navegador: para cada coleta gerada por este login com a pasta escolhida aqui, pede ao sistema o
 * próximo pedaço, grava em <pasta>/<nome>.partes no computador desta pessoa e confirma (só então a página do cliente
 * apaga a cópia dela). Com tudo gravado, um worker junta os pedaços em <nome>.tar.zst, confere cada arquivo pelo
 * SHA-256 do manifesto, grava o relatório <nome>.json e apaga os pedaços; o resultado vai para o sistema (e o sino).
 *
 * Fica rodando enquanto o sistema estiver aberto nesta aba (em qualquer tela). Fechou: o cliente espera e continua
 * quando abrir de novo.
 */

const espera = (ms) => new Promise((r) => setTimeout(r, ms))
const ATIVAS = ['AGUARDANDO', 'RECEBENDO', 'FECHANDO']
const nomeDaParte = (i) => `p${String(i).padStart(7, '0')}.zst`

async function shaHex(bytes) {
  const resumo = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(resumo), (b) => b.toString(16).padStart(2, '0')).join('')
}

async function gravarArquivo(pasta, nome, bytes) {
  const escrita = await (await pasta.getFileHandle(nome, { create: true })).createWritable()
  await escrita.write(bytes)
  await escrita.close()
}

async function existe(pasta, nome) {
  try {
    await pasta.getFileHandle(nome)
    return true
  } catch {
    return false
  }
}

/** Roda o worker que monta/extrai (os arquivos da página de coleta, servidos pelo sistema em /c/_/). */
function rodarWorker(pedido, aoAvancar) {
  return new Promise((resolver, rejeitar) => {
    const worker = new Worker('/c/_/montador-worker.js', { type: 'module' })
    worker.onmessage = (ev) => {
      if (ev.data.progresso) {
        aoAvancar(ev.data.progresso)
        return
      }
      worker.terminate()
      if (ev.data.erro) rejeitar(new Error(ev.data.erro))
      else resolver(ev.data.resultado)
    }
    worker.onerror = (ev) => {
      worker.terminate()
      rejeitar(new Error(ev.message || 'falha no worker'))
    }
    worker.postMessage(pedido)
  })
}

export const useRecepcaoStore = defineStore('recepcaoDeBackups', () => {
  /**
   * Por coleta: { fase, pasta, mensagem, bytes, progresso, extracao }. fase: 'recebendo' | 'permissao' (o navegador
   * pede para confirmar a pasta) | 'outro' (recebida em outro navegador) | 'montando' | 'concluido' | 'erro'.
   */
  const estados = reactive({})
  const lacos = new Map()

  function marcar(id, mudancas) {
    estados[id] = { ...(estados[id] ?? {}), ...mudancas }
  }

  /** Chamado pela tela a cada atualização: liga o recebimento das coletas deste login que estão em andamento. */
  async function acompanhar(coletas) {
    for (const c of coletas) {
      if (!c.minha) continue
      const registro = await pastaDaColeta(c.id)
      if (registro && (!estados[c.id]?.pasta || !estados[c.id]?.chave)) {
        marcar(c.id, { pasta: registro.pasta.name, chave: registro.chave ?? null })
      }
      if (!ATIVAS.includes(c.situacao) || lacos.has(c.id)) continue
      if (!registro) {
        marcar(c.id, { fase: 'outro' })
        continue
      }
      if ((await permissao(registro.pasta)) !== 'granted') {
        marcar(c.id, { fase: 'permissao', pasta: registro.pasta.name })
        continue
      }
      iniciar(c.id, registro)
    }
  }

  /** O clique que o navegador exige para voltar a gravar na pasta (depois de fechado). */
  async function permitir(id) {
    const registro = await pastaDaColeta(id)
    if (registro && (await pedirPermissao(registro.pasta))) iniciar(id, registro)
  }

  function iniciar(id, registro) {
    if (lacos.has(id)) return
    const controle = { ativo: true }
    lacos.set(id, controle)
    receber(id, registro, controle)
      .catch((e) => marcar(id, { fase: 'erro', mensagem: mensagemDe(e) }))
      .finally(() => lacos.delete(id))
  }

  async function receber(id, { pasta, nome, chave: textoDaChave }, controle) {
    marcar(id, { fase: 'recebendo', pasta: pasta.name, chave: textoDaChave ?? null, mensagem: '' })
    if (!textoDaChave) throw new Error('Esta coleta não tem a chave neste navegador: gere um link novo.')
    const chave = await importarChave(textoDaChave)
    let pausa = 2000
    while (controle.ativo) {
      try {
        const atual = await backupApi.coleta(id)
        if (atual.situacao === 'FECHANDO') {
          await fechar(id, pasta, nome)
          return
        }
        if (!ATIVAS.includes(atual.situacao)) {
          marcar(id, { fase: atual.situacao === 'CANCELADA' ? 'erro' : 'concluido', mensagem: atual.mensagem ?? '' })
          return
        }
        const partes = await pasta.getDirectoryHandle(`${nome}.partes`, { create: true })
        // pede e grava pedaços enquanto vierem; sem pedaço (204), volta a conferir a situação
        for (;;) {
          const resposta = await backupApi.receber(id)
          if (resposta.status !== 200) break
          const bytes = new Uint8Array(resposta.data)
          const seq = Number(resposta.headers['x-seq'])
          const sha = await shaHex(bytes)
          if (sha !== String(resposta.headers['x-sha256']).toLowerCase()) continue // estragou no caminho: pede de novo
          // decifra com a chave do link (só este navegador e a página do cliente a têm) e confere a etiqueta do GCM
          let conteudo
          try {
            conteudo = await decifrar(chave, bytes, seq)
          } catch {
            throw new PedacoAdulterado('Um pedaço chegou que não abre com a chave deste link (adulterado ou de outro '
              + 'link). Nada foi confirmado; cancele esta coleta e gere um link novo.')
          }
          await gravarArquivo(partes, nomeDaParte(seq), conteudo)
          await backupApi.recebido(id, seq, sha)
          marcar(id, { fase: 'recebendo', bytes: (estados[id]?.bytes ?? 0) + bytes.length, mensagem: '' })
          pausa = 2000
        }
      } catch (e) {
        if (e instanceof PedacoAdulterado || e?.status === 403 || e?.status === 404) throw e
        marcar(id, { mensagem: `${mensagemDe(e)} Tentando de novo…` })
        await espera(pausa)
        pausa = Math.min(pausa * 2, 30000)
      }
    }
  }

  async function fechar(id, pasta, nome) {
    marcar(id, { fase: 'montando', mensagem: 'Juntando os pedaços e conferindo cada arquivo…', progresso: null })
    let relatorio = null
    if (await existe(pasta, `${nome}.json`)) {
      // já montado antes (a confirmação ao sistema é que não chegou)
      const arquivo = await (await pasta.getFileHandle(`${nome}.json`)).getFile()
      relatorio = JSON.parse(await arquivo.text())
    }
    let resultado = relatorio?.conferencia
    if (!resultado) {
      const partes = await pasta.getDirectoryHandle(`${nome}.partes`, { create: true })
      const final = await backupApi.parteFinal(id)
      await gravarArquivo(partes, 'final.zst', new Uint8Array(final.data))
      const coleta = await backupApi.coleta(id)
      resultado = await rodarWorker({
        acao: 'montar',
        pasta,
        nome,
        quantidade: coleta.proximoSeq,
        relatorio: {
          cliente: coleta.cliente,
          raiz: coleta.raiz,
          pastas: coleta.pastas,
          computador: coleta.computador,
          geradoPor: coleta.criadaPor,
          inicio: coleta.inicioEnvio,
          fim: coleta.concluidaEm,
          resumo: coleta.resumo,
          alteradosDuranteALeitura: coleta.alterados,
          naoLidos: coleta.naoLidos,
        },
      }, (p) => marcar(id, { progresso: p }))
    }
    await backupApi.resultado(id, {
      integra: resultado.integra,
      arquivosConferidos: resultado.arquivosConferidos,
      bytesConferidos: resultado.bytesConferidos,
      tamanhoArquivo: resultado.tamanhoArquivo,
      divergentes: resultado.divergentes,
      faltando: resultado.faltando,
      sobrando: resultado.sobrando,
      mensagem: resultado.mensagem,
    })
    marcar(id, { fase: 'concluido', mensagem: resultado.mensagem, progresso: null })
  }

  /** Extrai um backup recebido neste navegador numa pasta escolhida agora (deste computador). */
  async function extrair(id) {
    const registro = await pastaDaColeta(id)
    if (!registro) throw new Error('Este backup não foi recebido neste navegador.')
    if ((await permissao(registro.pasta)) !== 'granted' && !(await pedirPermissao(registro.pasta))) {
      throw new Error('Sem permissão para ler a pasta do backup.')
    }
    const destino = await escolherPasta()
    if (!destino) return
    marcar(id, { extracao: { situacao: 'EXTRAINDO', destino: destino.name, arquivos: 0, bytes: 0 } })
    try {
      const r = await rodarWorker({ acao: 'extrair', pasta: registro.pasta, nome: registro.nome, destino },
        (p) => marcar(id, { extracao: { situacao: 'EXTRAINDO', destino: destino.name, arquivos: p.arquivos, bytes: p.bytes } }))
      const ignorados = r.ignorados?.length ? `; ${r.ignorados.length} ignorado(s) por terem nome que o Windows não aceita` : ''
      marcar(id, { extracao: { situacao: 'EXTRAIDO', destino: destino.name, arquivos: r.arquivos, bytes: r.bytes, mensagem: `${r.arquivos} arquivo(s) extraído(s) em “${destino.name}”${ignorados}.` } })
    } catch (e) {
      marcar(id, { extracao: { situacao: 'FALHOU', destino: destino.name, mensagem: e.message } })
    }
  }

  /** Uma coleta gerada agora neste navegador (a pasta e a chave já gravadas em destino.js). */
  function registrar(id, pasta, chave) {
    marcar(id, { pasta: pasta.name, chave })
  }

  return { estados, acompanhar, permitir, extrair, registrar }
})
