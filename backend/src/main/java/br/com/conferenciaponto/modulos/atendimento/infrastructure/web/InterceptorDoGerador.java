package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Toda rota do gerador (menos o "meu acesso" e a tela de acessos do administrador) é só para quem foi liberado
 * em atendimento.acesso: as rotas novas já nascem protegidas. A liberação é relida a cada requisição, então
 * retirar o acesso vale na hora.
 */
final class InterceptorDoGerador implements HandlerInterceptor {

    private final AcessoUsuarios acesso;
    private final GerenciarAcessosAoGerador acessos;

    InterceptorDoGerador(AcessoUsuarios acesso, GerenciarAcessosAoGerador acessos) {
        this.acesso = acesso;
        this.acessos = acessos;
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
        if (!acessos.liberado(logado)) {
            throw new AcessoNegadoException("MODULO_NAO_LIBERADO",
                    "O gerador de atendimentos não está liberado para você. Peça ao administrador.");
        }
        return true;
    }
}
