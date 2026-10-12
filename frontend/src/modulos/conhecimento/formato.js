/** Textos, listas e limites da base de conhecimento (os limites são os do servidor, que é quem confere). */

export const TIPOS_DO_REGISTRO = [
  { codigo: 'duvida', rotulo: 'Dúvida' },
  { codigo: 'erro', rotulo: 'Erro' },
  { codigo: 'alteracao', rotulo: 'Alteração' },
]

export const SITUACOES_DO_REGISTRO = [
  { codigo: 'resolvido_suporte', rotulo: 'Resolvido no suporte', classe: 'border-credito/50 text-credito' },
  { codigo: 'enviado_desenvolvimento', rotulo: 'Enviado ao desenvolvimento', classe: 'border-tinta/40 text-tinta' },
  { codigo: 'corrigido', rotulo: 'Corrigido', classe: 'border-credito/50 text-credito' },
  { codigo: 'nao_sera_feito', rotulo: 'Não será feito', classe: 'border-carimbo/50 text-carimbo' },
]

export const rotuloDoTipo = (codigo) => TIPOS_DO_REGISTRO.find((t) => t.codigo === codigo)?.rotulo ?? codigo
export const situacaoDoRegistro = (codigo) => SITUACOES_DO_REGISTRO.find((s) => s.codigo === codigo)

export const LIMITES_DO_REGISTRO = {
  titulo: 200,
  cliente: 150,
  contato: 150,
  modulo: 300,
  telaNome: 200,
  telaCodigo: 40,
  sintoma: 20000,
  mensagemErro: 20000,
  causa: 20000,
  resolucao: 20000,
  chamadoDev: 60,
}

const NOMES = {
  titulo: 'O título',
  cliente: 'O cliente',
  contato: 'O contato',
  modulo: 'O módulo',
  telaNome: 'A tela',
  telaCodigo: 'O código da tela',
  sintoma: 'O sintoma',
  mensagemErro: 'A mensagem de erro',
  causa: 'A causa',
  resolucao: 'A resolução',
  chamadoDev: 'O chamado no desenvolvimento',
}

const CAMPOS = ['titulo', 'tipo', 'cliente', 'contato', 'modulo', 'telaNome', 'telaCodigo', 'sintoma', 'mensagemErro', 'causa', 'resolucao']

/** O registro para o formulário (null vira texto vazio). Com situação, também a situação e o chamado. */
export function formularioDoRegistro(registro, comSituacao = false) {
  const form = Object.fromEntries(CAMPOS.map((c) => [c, registro?.[c] ?? '']))
  if (!form.tipo) form.tipo = 'duvida'
  if (comSituacao) {
    form.situacao = registro?.situacao ?? 'resolvido_suporte'
    form.chamadoDev = registro?.chamadoDev ?? ''
  }
  return form
}

/** O mesmo formulário (para saber se a pessoa mudou alguma coisa). */
export function mesmoFormulario(a, b) {
  return Object.keys(a).every((c) => String(a[c] ?? '').trim() === String(b?.[c] ?? '').trim())
}

/** "O título é obrigatório.", "O código da tela tem 52 caracteres; o limite é 40." (a mesma conta do servidor). */
export function problemasDoRegistro(form) {
  const problemas = []
  if (!String(form?.titulo ?? '').trim()) problemas.push('Escreva o título.')
  for (const [campo, limite] of Object.entries(LIMITES_DO_REGISTRO)) {
    if (!(campo in (form ?? {}))) continue
    const tamanho = String(form[campo] ?? '').trim().length
    if (tamanho > limite) problemas.push(`${NOMES[campo]} tem ${tamanho} caracteres; o limite é ${limite}.`)
  }
  return problemas
}

const DATA = { day: '2-digit', month: '2-digit', year: 'numeric' }
const DATA_HORA = { ...DATA, hour: '2-digit', minute: '2-digit' }

export const data = (iso) => (iso ? new Date(iso).toLocaleDateString('pt-BR', DATA) : '—')
export const dataHora = (iso) => (iso ? new Date(iso).toLocaleString('pt-BR', DATA_HORA).replace(', ', ' às ') : '—')
