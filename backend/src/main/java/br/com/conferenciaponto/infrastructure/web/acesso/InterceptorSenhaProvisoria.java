package br.com.conferenciaponto.infrastructure.web.acesso;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Com a senha provisória (primeiro acesso ou senha redefinida pelo administrador) a pessoa só consegue entrar,
 * consultar a própria sessão e trocar a senha: todas as outras rotas da API respondem 403 TROCAR_SENHA, e a
 * tela leva à troca. Vale para qualquer rota, inclusive as do administrador.
 */
@Component
public class InterceptorSenhaProvisoria implements HandlerInterceptor {

    private final AcessoUsuarios acesso;

    public InterceptorSenhaProvisoria(AcessoUsuarios acesso) {
        this.acesso = acesso;
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object destino) {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !autenticacao.isAuthenticated() || autenticacao instanceof AnonymousAuthenticationToken) {
            return true;
        }
        if (acesso.logado(autenticacao.getName()).trocarSenha()) {
            throw new AcessoNegadoException("TROCAR_SENHA", "Troque a senha provisória para continuar.");
        }
        return true;
    }
}
