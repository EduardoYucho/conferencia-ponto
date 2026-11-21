package br.com.expedio.modulos.backup.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A porta do túnel (a que o cloudflared publica na internet) atende só a página de coleta: {@code /c/...}. Qualquer
 * outra rota do sistema pedida por ela responde 404, sem chegar ao resto da aplicação. Pela porta de sempre nada muda
 * (a página de coleta também abre por ela, e vai para o túnel).
 *
 * <p>Em todo /c/...: barra quem está testando links (veja {@link Bloqueios}) e põe os cabeçalhos de proteção da página
 * (só carrega o que vem do próprio sistema, não abre dentro de outro site, não passa o link adiante).
 */
public class FiltroDaPortaPublica extends OncePerRequestFilter {

    /**
     * A página só roda os próprios arquivos (o wasm do zstd precisa de 'wasm-unsafe-eval'), só fala com o próprio
     * sistema e não pode ser posta dentro de outro site.
     */
    static final String POLITICA = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; "
            + "connect-src 'self'; style-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private final int portaPublica;
    private final Bloqueios bloqueios;

    public FiltroDaPortaPublica(int portaPublica, Bloqueios bloqueios) {
        this.portaPublica = portaPublica;
        this.bloqueios = bloqueios;
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
            if (bloqueios != null && bloqueios.barrado(Bloqueios.enderecoDe(requisicao, portaPublica))) {
                resposta.setStatus(429);
                resposta.setContentType("text/plain;charset=UTF-8");
                resposta.getWriter().write("Muitas tentativas com links inválidos. Tente de novo mais tarde.");
                return;
            }
            resposta.setHeader("Content-Security-Policy", POLITICA);
            resposta.setHeader("Referrer-Policy", "no-referrer");
            resposta.setHeader("X-Content-Type-Options", "nosniff");
            resposta.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            resposta.setHeader("Cross-Origin-Resource-Policy", "same-origin");
            resposta.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
            if (requisicao.isSecure() || "https".equalsIgnoreCase(requisicao.getHeader("X-Forwarded-Proto"))) {
                resposta.setHeader("Strict-Transport-Security", "max-age=31536000");
            }
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
