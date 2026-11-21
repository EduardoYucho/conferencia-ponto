package br.com.expedio.modulos.backup.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quem fica tentando links de coleta que não existem (20 em 10 minutos) é barrado por 30 minutos em todo /c/...
 * O token tem 32 bytes aleatórios (adivinhar é impossível na prática); isto corta o barulho e as varreduras.
 *
 * <p>O endereço é o do visitante: pelo túnel, o que o Cloudflare informa (CF-Connecting-IP; esse cabeçalho só vale
 * em pedido que chegou pela porta do túnel, onde só o cloudflared fala); pela rede, o do próprio pedido.
 */
@Component
public class Bloqueios {

    private static final Logger LOG = LoggerFactory.getLogger(Bloqueios.class);
    static final int TENTATIVAS = 20;
    static final long JANELA_MS = 10 * 60_000L;
    static final long BLOQUEIO_MS = 30 * 60_000L;

    private final Map<String, Deque<Long>> erros = new ConcurrentHashMap<>();
    private final Map<String, Long> barrados = new ConcurrentHashMap<>();

    static String enderecoDe(HttpServletRequest requisicao, int portaPublica) {
        String doCloudflare = requisicao.getHeader("CF-Connecting-IP");
        if (portaPublica > 0 && requisicao.getLocalPort() == portaPublica && doCloudflare != null && !doCloudflare.isBlank()) {
            return doCloudflare.trim();
        }
        return requisicao.getRemoteAddr();
    }

    public boolean barrado(String endereco) {
        Long ate = barrados.get(endereco);
        if (ate == null) {
            return false;
        }
        if (System.currentTimeMillis() > ate) {
            barrados.remove(endereco);
            return false;
        }
        return true;
    }

    /** Um link inexistente pedido por este endereço. */
    public void registrarErro(String endereco) {
        long agora = System.currentTimeMillis();
        Deque<Long> fila = erros.computeIfAbsent(endereco, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(agora);
            while (!fila.isEmpty() && agora - fila.peekFirst() > JANELA_MS) {
                fila.removeFirst();
            }
            if (fila.size() >= TENTATIVAS) {
                barrados.put(endereco, agora + BLOQUEIO_MS);
                fila.clear();
                LOG.warn("Backups de clientes: {} barrado por 30 min (muitos links de coleta inexistentes)", endereco);
            }
        }
        if (erros.size() > 10_000) {
            erros.clear(); // não deixa a memória crescer sem limite numa varredura grande
        }
    }
}
