import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/authApi'
import { pontoApi } from '@/api/pontoApi'

const CHAVE = 'conferencia-ponto.sessao'

function lerSessaoSalva() {
  try {
    const sessao = JSON.parse(localStorage.getItem(CHAVE) ?? 'null')
    if (sessao?.token && new Date(sessao.expiraEm) > new Date()) return sessao
  } catch {
    // armazenamento indisponível (modo privado) ou conteúdo inválido
  }
  return null
}

/**
 * Sessão do usuário (JWT) e de quem são os dados em tela.
 *
 * Cada pessoa vê e altera os próprios dados. Administrador e coordenação podem consultar os dados de
 * qualquer pessoa ("ver como"): nesse caso as telas ficam somente leitura — ninguém altera o ponto de outra
 * pessoa. O token vale 8 h por padrão e fica no localStorage para sobreviver a um recarregamento da página.
 */
export const useAuthStore = defineStore('auth', () => {
  const salva = lerSessaoSalva()
  const token = ref(salva?.token ?? null)
  const expiraEm = ref(salva?.expiraEm ?? null)
  const usuario = ref(salva?.usuario ?? null)
  /** Login da pessoa cujos dados estão em tela, quando não é o próprio usuário (admin/coordenação). */
  const visto = ref(salva?.visto ?? null)
  /** Pessoas com dados de ponto que o usuário pode consultar: [{ id, login, nome }]. */
  const titulares = ref([])
  /** A lista de pessoas não pôde ser carregada (as telas avisam e oferecem tentar de novo). */
  const erroTitulares = ref(null)
  /** Por que a sessão terminou sozinha (a tela de login explica): texto ou null. */
  const avisoDeSaida = ref(null)
  let timerExpiracao = null
  let aoExpirar = () => {}

  const autenticado = computed(() => !!token.value && new Date(expiraEm.value) > new Date())
  const perfis = computed(() => usuario.value?.perfis ?? [])
  const ehAdmin = computed(() => perfis.value.includes('ROLE_ADMIN'))
  /** Tem os próprios dados de ponto (ADMIN/USER). */
  const ehTitular = computed(() => usuario.value?.titular ?? usuario.value?.podeEscrever === true)
  /** ADMIN e coordenação: escolhem de quem são os dados em tela. */
  const podeVerTodos = computed(() => usuario.value?.podeVerTodos ?? (ehAdmin.value || perfis.value.includes('ROLE_VIEWER')))
  const precisaTrocarSenha = computed(() => usuario.value?.trocarSenha === true)

  /** Os dados em tela são do próprio usuário. */
  const vendoOsProprios = computed(() => !visto.value || visto.value === usuario.value?.login)
  /** Pessoa em tela: { id, login, nome }. */
  const pessoaEmTela = computed(() => {
    if (vendoOsProprios.value && ehTitular.value) {
      return { id: usuario.value?.id, login: usuario.value?.login, nome: usuario.value?.nome }
    }
    return titulares.value.find((t) => t.login === visto.value) ?? (visto.value ? { id: null, login: visto.value, nome: visto.value } : null)
  })
  /** Id da pessoa em tela (filtra os eventos em tempo real de outras pessoas). */
  const idEmTela = computed(() => pessoaEmTela.value?.id ?? null)

  /** ADMIN/USER vendo os próprios dados: escrita liberada. */
  const podeEscrever = computed(() => usuario.value?.podeEscrever === true && vendoOsProprios.value)
  /** Coordenação, ou admin consultando outra pessoa: sem botões de edição/inserção/exclusão. */
  const somenteLeitura = computed(() => autenticado.value && !podeEscrever.value)
  const rotuloPerfil = computed(() => {
    if (ehAdmin.value) return 'Administrador'
    if (perfis.value.includes('ROLE_USER')) return 'Usuário'
    if (perfis.value.includes('ROLE_VIEWER')) return 'Coordenação (leitura)'
    return ''
  })

  function persistir() {
    try {
      if (token.value) {
        localStorage.setItem(CHAVE, JSON.stringify({
          token: token.value, expiraEm: expiraEm.value, usuario: usuario.value, visto: visto.value,
        }))
      } else {
        localStorage.removeItem(CHAVE)
      }
    } catch {
      // sem persistência: a sessão vale até fechar a aba
    }
  }

  /**
   * O token tem hora para vencer: em vez de esperar a próxima ação dar erro, a sessão é encerrada na hora
   * certa, com explicação. (O setTimeout do navegador não aceita esperas acima de ~24 dias.)
   */
  function agendarExpiracao() {
    clearTimeout(timerExpiracao)
    if (!token.value || !expiraEm.value) return
    const falta = new Date(expiraEm.value).getTime() - Date.now()
    timerExpiracao = setTimeout(() => {
      if (token.value && new Date(expiraEm.value) <= new Date()) aoExpirar()
      else agendarExpiracao()
    }, Math.min(Math.max(falta, 0) + 500, 2_000_000_000))
  }

  /** Registrado no main.js: o que fazer quando o token vence com a aba aberta. */
  function definirAoExpirar(acao) {
    aoExpirar = acao
    agendarExpiracao()
  }

  async function login(loginInformado, senha) {
    const sessao = await authApi.login(loginInformado, senha)
    token.value = sessao.token
    expiraEm.value = sessao.expiraEm
    usuario.value = sessao.usuario
    visto.value = null
    avisoDeSaida.value = null
    persistir()
    agendarExpiracao()
    if (!sessao.usuario.trocarSenha) await carregarTitulares().catch(() => {})
    return sessao.usuario
  }

  /** @param {string|null} [aviso] por que a sessão terminou sem a pessoa pedir (mostrado no login) */
  function logout(aviso = null) {
    clearTimeout(timerExpiracao)
    token.value = null
    expiraEm.value = null
    usuario.value = null
    visto.value = null
    titulares.value = []
    erroTitulares.value = null
    avisoDeSaida.value = aviso
    persistir()
  }

  /** Relê o usuário logado (perfil, pasta, senha provisória). */
  async function atualizarUsuario() {
    usuario.value = await authApi.me()
    persistir()
    return usuario.value
  }

  async function alterarSenha(senhaAtual, novaSenha) {
    await authApi.alterarSenha(senhaAtual, novaSenha)
    if (usuario.value?.trocarSenha) {
      usuario.value = { ...usuario.value, trocarSenha: false }
      persistir()
      await carregarTitulares().catch(() => {})
    }
  }

  /**
   * Lista de pessoas que dá para consultar. A coordenação não tem dados próprios: começa vendo a primeira
   * pessoa da lista. Se a pessoa em tela deixou de existir (desativada), volta para os próprios dados.
   */
  async function carregarTitulares() {
    try {
      titulares.value = await pontoApi.titulares()
      erroTitulares.value = null
    } catch (e) {
      erroTitulares.value = e
      throw e
    }
    if (visto.value && !titulares.value.some((t) => t.login === visto.value)) visto.value = null
    if (!visto.value && !ehTitular.value) visto.value = titulares.value[0]?.login ?? null
    persistir()
    return titulares.value
  }

  /** Troca a pessoa em tela (null = os próprios dados). */
  function verComo(login) {
    visto.value = !login || login === usuario.value?.login ? null : login
    if (!visto.value && !ehTitular.value) visto.value = titulares.value[0]?.login ?? null
    persistir()
  }

  /** Login que vai como ?usuario= nas consultas (null = os próprios dados). */
  function loginConsultado() {
    return vendoOsProprios.value ? null : visto.value
  }

  /** Rota inicial conforme o perfil: coordenação cai direto na auditoria. */
  function rotaInicial() {
    if (precisaTrocarSenha.value) return { name: 'trocar-senha' }
    return ehTitular.value ? { name: 'painel' } : { name: 'auditoria' }
  }

  return {
    token, expiraEm, usuario, visto, titulares, erroTitulares, avisoDeSaida,
    autenticado, perfis, ehAdmin, ehTitular, podeVerTodos, precisaTrocarSenha, vendoOsProprios, pessoaEmTela,
    idEmTela, podeEscrever, somenteLeitura, rotuloPerfil,
    definirAoExpirar, login, logout, atualizarUsuario, alterarSenha, carregarTitulares, verComo, loginConsultado, rotaInicial,
  }
})
