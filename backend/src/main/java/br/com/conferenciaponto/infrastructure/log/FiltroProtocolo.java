package br.com.conferenciaponto.infrastructure.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Primeiro filtro de cada requisição da API: dá a ela um protocolo (devolvido no cabeçalho {@code X-Protocolo} e
 * nas respostas de erro) e, no fim, registra uma linha com quem pediu, o quê, o resultado e quanto demorou.
 *
 * <p>O usuário é descoberto depois, dentro da cadeia de segurança ({@link FiltroUsuarioNoLog}); por isso a linha
 * de acesso é escrita aqui, na volta, com o usuário que ficou guardado na requisição.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroProtocolo extends OncePerRequestFilter {

    public static final String CABECALHO = "X-Protocolo";
    static final String ATRIBUTO_USUARIO = FiltroProtocolo.class.getName() + ".usuario";

    private static final Logger log = LoggerFactory.getLogger("acesso");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        // telas (HTML, JS, CSS) não são registradas: só a API
        String caminho = requisicao.getRequestURI().substring(requisicao.getContextPath().length());
        return !caminho.equals("/api") && !caminho.startsWith("/api/");
    }

    /** O stream de eventos fica aberto por minutos: a linha de acesso é escrita por quem o abre, não na volta. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String protocolo = ContextoDeLog.novoProtocolo();
        MDC.put(ContextoDeLog.PROTOCOLO, protocolo);
        resposta.setHeader(CABECALHO, protocolo);
        long inicio = System.nanoTime();
        try {
            cadeia.doFilter(requisicao, resposta);
        } finally {
            registrar(requisicao, resposta, (System.nanoTime() - inicio) / 1_000_000);
            MDC.remove(ContextoDeLog.PROTOCOLO);
            MDC.remove(ContextoDeLog.USUARIO);
        }
    }

    private static void registrar(HttpServletRequest requisicao, HttpServletResponse resposta, long milissegundos) {
        Object usuario = requisicao.getAttribute(ATRIBUTO_USUARIO);
        if (usuario != null) {
            MDC.put(ContextoDeLog.USUARIO, usuario.toString());
        }
        String caminho = requisicao.getRequestURI();
        int status = resposta.getStatus();
        // a tela de logs consulta a cada 2 s e o stream de eventos fica aberto: não entram como linha de acesso
        boolean rotina = caminho.endsWith("/logs/ao-vivo") || requisicao.isAsyncStarted();
        if (rotina && status < 400) {
            return;
        }
        String consulta = requisicao.getQueryString();
        String linha = "%s %s%s -> %d (%d ms)".formatted(requisicao.getMethod(), caminho,
                consulta == null ? "" : "?" + consulta, status, milissegundos);
        if (status >= 500) {
            log.error(linha);
        } else if (status >= 400) {
            log.warn(linha);
        } else {
            log.info(linha);
        }
    }
}
