package br.com.conferenciaponto.application.seguranca;

import br.com.conferenciaponto.domain.exception.MuitasTentativasException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freio contra quem tenta adivinhar a senha: {@value #ERROS_POR_LOGIN} senhas erradas para o mesmo login em
 * {@link #JANELA} bloqueiam esse login por {@link #BLOQUEIO}; {@value #ERROS_POR_ORIGEM} erros vindos do mesmo
 * computador (qualquer login) bloqueiam o computador pelo mesmo tempo. Entrar com a senha certa zera o login.
 *
 * <p>Fica na memória: reiniciar o sistema libera tudo (quem tenta adivinhar não consegue reiniciar o servidor), e o
 * tamanho é limitado ({@value #MAXIMO_DE_CHAVES} logins ou computadores) para uma enxurrada de logins inventados
 * não ocupar a memória.
 */
@Component
public class TentativasDeLogin {

    private static final Logger log = LoggerFactory.getLogger(TentativasDeLogin.class);

    static final int ERROS_POR_LOGIN = 5;
    static final int ERROS_POR_ORIGEM = 20;
    static final Duration JANELA = Duration.ofMinutes(15);
    static final Duration BLOQUEIO = Duration.ofMinutes(15);
    static final int MAXIMO_DE_CHAVES = 5_000;

    /** Os erros recentes e, se for o caso, até quando está bloqueado. */
    private static final class Registro {
        final Deque<Instant> erros = new ArrayDeque<>();
        Instant bloqueadoAte;
    }

    private final Map<String, Registro> porLogin = new ConcurrentHashMap<>();
    private final Map<String, Registro> porOrigem = new ConcurrentHashMap<>();
    private final Clock clock;

    public TentativasDeLogin(Clock clock) {
        this.clock = clock;
    }

    /** Antes de conferir a senha: recusa (429) o login ou o computador bloqueado. */
    public void conferir(String login, String origem) {
        Instant agora = clock.instant();
        Duration espera = esperaDe(porLogin.get(chave(login)), agora);
        if (espera != null) {
            throw new MuitasTentativasException("LOGIN_BLOQUEADO", ("Muitas senhas erradas para este login. Espere %s "
                    + "e tente de novo; se esqueceu a senha, peça ao administrador uma senha provisória.")
                    .formatted(minutos(espera)), espera);
        }
        espera = esperaDe(origem == null ? null : porOrigem.get(origem), agora);
        if (espera != null) {
            throw new MuitasTentativasException("LOGIN_BLOQUEADO", ("Muitas tentativas erradas a partir deste "
                    + "computador. Espere %s e tente de novo.").formatted(minutos(espera)), espera);
        }
    }

    /** Uma senha errada (ou um login que não existe). */
    public void falhou(String login, String origem) {
        Instant agora = clock.instant();
        if (registrar(porLogin, chave(login), agora, ERROS_POR_LOGIN)) {
            log.warn("Login \"{}\" bloqueado por {} min depois de {} senhas erradas", chave(login), BLOQUEIO.toMinutes(),
                    ERROS_POR_LOGIN);
        }
        if (origem != null && registrar(porOrigem, origem, agora, ERROS_POR_ORIGEM)) {
            log.warn("Computador {} bloqueado por {} min depois de {} tentativas de login erradas", origem,
                    BLOQUEIO.toMinutes(), ERROS_POR_ORIGEM);
        }
    }

    /** Entrou com a senha certa: os erros desse login saem (os do computador continuam contando). */
    public void entrou(String login) {
        porLogin.remove(chave(login));
    }

    /** @return true se este erro bloqueou */
    private static boolean registrar(Map<String, Registro> mapa, String chave, Instant agora, int limite) {
        if (mapa.size() >= MAXIMO_DE_CHAVES && !mapa.containsKey(chave)) {
            limpar(mapa, agora);
            if (mapa.size() >= MAXIMO_DE_CHAVES) {
                mapa.clear(); // não sobra nada recente para guardar: começa de novo
            }
        }
        Registro r = mapa.computeIfAbsent(chave, c -> new Registro());
        synchronized (r) {
            descartarAntigos(r, agora);
            r.erros.addLast(agora);
            if (r.erros.size() >= limite && (r.bloqueadoAte == null || !r.bloqueadoAte.isAfter(agora))) {
                r.bloqueadoAte = agora.plus(BLOQUEIO);
                r.erros.clear();
                return true;
            }
            return false;
        }
    }

    private static Duration esperaDe(Registro r, Instant agora) {
        if (r == null) {
            return null;
        }
        synchronized (r) {
            if (r.bloqueadoAte == null || !r.bloqueadoAte.isAfter(agora)) {
                return null;
            }
            return Duration.between(agora, r.bloqueadoAte);
        }
    }

    private static void descartarAntigos(Registro r, Instant agora) {
        Instant limite = agora.minus(JANELA);
        while (!r.erros.isEmpty() && r.erros.peekFirst().isBefore(limite)) {
            r.erros.removeFirst();
        }
    }

    private static void limpar(Map<String, Registro> mapa, Instant agora) {
        mapa.entrySet().removeIf(e -> {
            Registro r = e.getValue();
            synchronized (r) {
                descartarAntigos(r, agora);
                return r.erros.isEmpty() && (r.bloqueadoAte == null || !r.bloqueadoAte.isAfter(agora));
            }
        });
    }

    private static String chave(String login) {
        String limpo = login == null ? "" : login.replaceAll("\\p{Cntrl}", " ").strip().toLowerCase(Locale.ROOT);
        return limpo.length() > 60 ? limpo.substring(0, 60) : limpo;
    }

    private static String minutos(Duration espera) {
        long m = Math.max(1, (espera.toSeconds() + 59) / 60);
        return m == 1 ? "1 minuto" : m + " minutos";
    }
}
