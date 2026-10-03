package br.com.conferenciaponto.infrastructure.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Depois que o token é conferido, anota o login no contexto de log: a partir daqui toda linha da requisição cai
 * no arquivo do usuário. Fica dentro da cadeia de segurança (ver SecurityConfig), logo depois da autenticação.
 */
public class FiltroUsuarioNoLog extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.isAuthenticated()
                && !(autenticacao instanceof AnonymousAuthenticationToken) && autenticacao.getName() != null) {
            ContextoDeLog.definirUsuario(autenticacao.getName());
            requisicao.setAttribute(FiltroProtocolo.ATRIBUTO_USUARIO, autenticacao.getName());
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
