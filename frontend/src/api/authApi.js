import { http } from './http'

export const authApi = {
  /** @returns {{ token, tipo, expiraEm, usuario: { login, nome, perfis, podeEscrever } }} */
  login: (login, senha) => http.post('/auth/login', { login, senha }),

  me: () => http.get('/auth/me'),

  alterarSenha: (senhaAtual, novaSenha) => http.put('/auth/senha', { senhaAtual, novaSenha }),
}
