import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/authApi'

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
 * Sessão do usuário (JWT). O token vale 8 h por padrão e fica no localStorage para
 * sobreviver a um recarregamento da página; ao expirar, a API responde 401 e a sessão é limpa.
 */
export const useAuthStore = defineStore('auth', () => {
  const salva = lerSessaoSalva()
  const token = ref(salva?.token ?? null)
  const expiraEm = ref(salva?.expiraEm ?? null)
  const usuario = ref(salva?.usuario ?? null)

  const autenticado = computed(() => !!token.value && new Date(expiraEm.value) > new Date())
  const perfis = computed(() => usuario.value?.perfis ?? [])
  /** ROLE_ADMIN / ROLE_USER: escrita liberada. */
  const podeEscrever = computed(() => usuario.value?.podeEscrever === true)
  /** ROLE_VIEWER: interface simplificada, sem botões de edição/inserção/exclusão. */
  const somenteLeitura = computed(() => autenticado.value && !podeEscrever.value)
  const rotuloPerfil = computed(() => {
    if (perfis.value.includes('ROLE_ADMIN')) return 'Administrador'
    if (perfis.value.includes('ROLE_USER')) return 'Usuário'
    if (perfis.value.includes('ROLE_VIEWER')) return 'Auditoria (leitura)'
    return ''
  })

  function persistir() {
    try {
      if (token.value) {
        localStorage.setItem(CHAVE, JSON.stringify({ token: token.value, expiraEm: expiraEm.value, usuario: usuario.value }))
      } else {
        localStorage.removeItem(CHAVE)
      }
    } catch {
      // sem persistência: a sessão vale até fechar a aba
    }
  }

  async function login(loginInformado, senha) {
    const sessao = await authApi.login(loginInformado, senha)
    token.value = sessao.token
    expiraEm.value = sessao.expiraEm
    usuario.value = sessao.usuario
    persistir()
    return sessao.usuario
  }

  function logout() {
    token.value = null
    expiraEm.value = null
    usuario.value = null
    persistir()
  }

  function alterarSenha(senhaAtual, novaSenha) {
    return authApi.alterarSenha(senhaAtual, novaSenha)
  }

  /** Rota inicial conforme o perfil: coordenação cai direto na auditoria. */
  function rotaInicial() {
    return somenteLeitura.value ? { name: 'auditoria' } : { name: 'painel' }
  }

  return {
    token, expiraEm, usuario,
    autenticado, perfis, podeEscrever, somenteLeitura, rotuloPerfil,
    login, logout, alterarSenha, rotaInicial,
  }
})
