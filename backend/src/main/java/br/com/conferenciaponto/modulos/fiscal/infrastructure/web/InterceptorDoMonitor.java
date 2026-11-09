package br.com.conferenciaponto.modulos.fiscal.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.fiscal.application.acesso.PermissoesDoMonitor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Toda rota do monitor (menos o "meu acesso") exige fiscal.ver: as rotas novas já nascem protegidas. O acesso é
 * relido a cada requisição, então retirar vale na hora.
 */
final class InterceptorDoMonitor implements HandlerInterceptor {

    private final AcessoUsuarios acesso;

    InterceptorDoMonitor(AcessoUsuarios acesso) {
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
        if (!logado.pode(PermissoesDoMonitor.VER)) {
            throw new AcessoNegadoException("MODULO_NAO_LIBERADO",
                    "O monitor fiscal não está liberado para você. Peça a quem cuida dos acessos.");
        }
        return true;
    }
}
