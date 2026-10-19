package br.com.conferenciaponto.modulos.conhecimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.PermissoesDaBase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Toda rota da base (menos o "meu acesso") exige base.pesquisar: as rotas novas já nascem protegidas. O que só a
 * curadoria faz é conferido no caso de uso. O acesso é relido a cada requisição, então retirar vale na hora.
 */
final class InterceptorDaBase implements HandlerInterceptor {

    private final AcessoUsuarios acesso;

    InterceptorDaBase(AcessoUsuarios acesso) {
        this.acesso = acesso;
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object destino) {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (CorsUtils.isPreFlightRequest(requisicao) || autenticacao == null || !autenticacao.isAuthenticated()
                || autenticacao instanceof AnonymousAuthenticationToken) {
            return true; // a sessão é exigida antes, pela cadeia de segurança do módulo
        }
        Usuario logado = acesso.logado();
        if (logado.trocarSenha()) {
            throw new AcessoNegadoException("TROCAR_SENHA", "Troque a senha provisória para continuar.");
        }
        if (!logado.pode(PermissoesDaBase.PESQUISAR)) {
            throw new AcessoNegadoException("MODULO_NAO_LIBERADO",
                    "A base de conhecimento não está liberada para você. Peça a quem cuida dos acessos.");
        }
        return true;
    }
}
