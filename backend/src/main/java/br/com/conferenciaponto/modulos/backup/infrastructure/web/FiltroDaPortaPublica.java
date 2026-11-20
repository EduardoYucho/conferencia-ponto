package br.com.conferenciaponto.modulos.backup.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A porta pública (a que o túnel do cloudflared publica na internet) atende só a página de coleta: {@code /c/...}.
 * Qualquer outra rota do sistema pedida por ela responde 404, sem chegar ao resto da aplicação. Pela porta de sempre
 * nada muda (a página de coleta também abre por ela, para testar na rede).
 */
public class FiltroDaPortaPublica extends OncePerRequestFilter {

    private final int portaPublica;

    public FiltroDaPortaPublica(int portaPublica) {
        this.portaPublica = portaPublica;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String caminho = requisicao.getRequestURI().substring(requisicao.getContextPath().length());
        boolean daColeta = caminho.startsWith("/c/");
        if (portaPublica > 0 && requisicao.getLocalPort() == portaPublica && !daColeta) {
            resposta.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (daColeta) {
            // o token vai no endereço: nada de repassá-lo a outro site
            resposta.setHeader("Referrer-Policy", "no-referrer");
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
