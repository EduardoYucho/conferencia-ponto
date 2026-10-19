package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Toda rota do gerador (menos o "meu acesso" e o espaço em disco) é só para quem tem atendimentos.usar: as rotas
 * novas já nascem protegidas. O acesso é relido a cada requisição, então retirar vale na hora.
 */
final class InterceptorDoGerador implements HandlerInterceptor {

    private final AcessoUsuarios acesso;

    InterceptorDoGerador(AcessoUsuarios acesso) {
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
        if (!logado.pode(PermissoesDoAtendimento.USAR)) {
            throw new AcessoNegadoException("MODULO_NAO_LIBERADO",
                    "Os atendimentos não estão liberados para você. Peça a quem cuida dos acessos.");
        }
        return true;
    }
}
