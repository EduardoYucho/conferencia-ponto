package br.com.receptorbackup.config;

import java.io.IOException;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Separa o que é público do que é só desta máquina.
 * <ul>
 * <li>Porta pública (a do túnel): só /c/... Qualquer outra coisa responde 404.</li>
 * <li>Porta da tela: recusa o que veio pelo túnel (cabeçalho do Cloudflare), um Host que não seja localhost (contra
 * DNS rebinding) e, nas requisições que mudam algo, exige o cabeçalho X-Receptor (um site de fora não consegue
 * mandá-lo sem permissão de CORS, que o Receptor nunca dá).</li>
 * </ul>
 */
public class FiltroDePortas extends OncePerRequestFilter {

    private static final Set<String> METODOS_SEGUROS = Set.of("GET", "HEAD", "OPTIONS");

    private final Propriedades propriedades;

    public FiltroDePortas(Propriedades propriedades) {
        this.propriedades = propriedades;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String caminho = requisicao.getRequestURI();
        resposta.setHeader("X-Content-Type-Options", "nosniff");
        resposta.setHeader("Referrer-Policy", "no-referrer");
        resposta.setHeader("X-Frame-Options", "DENY");

        if (requisicao.getLocalPort() == propriedades.portaPublica()) {
            if (!caminho.startsWith("/c/")) {
                resposta.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            cadeia.doFilter(requisicao, resposta);
            return;
        }

        if (requisicao.getHeader("Cf-Connecting-Ip") != null || requisicao.getHeader("Cf-Ray") != null) {
            resposta.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (!hostLocal(requisicao.getHeader("Host"), requisicao.getLocalPort())) {
            resposta.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (caminho.startsWith("/api/") && !METODOS_SEGUROS.contains(requisicao.getMethod())
                && !"1".equals(requisicao.getHeader("X-Receptor"))) {
            resposta.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }

    static boolean hostLocal(String host, int porta) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        return h.equals("localhost:" + porta) || h.equals("127.0.0.1:" + porta) || h.equals("[::1]:" + porta);
    }
}
