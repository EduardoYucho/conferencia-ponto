package br.com.conferenciaponto.infrastructure.log;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * O que acompanha cada linha de log: de quem é a ação ({@code usuario}) e o protocolo da requisição.
 *
 * <p>O usuário decide em qual arquivo a linha cai ({@code logs/usuarios/<login>/<data>/<hora>h.log}); o que não
 * é de ninguém (subida do sistema, tarefas agendadas) vai para {@link #SISTEMA}. O protocolo é o código curto
 * que a tela mostra junto de um erro inesperado: com ele se acha a linha exata no log.
 */
public final class ContextoDeLog {

    public static final String USUARIO = "usuario";
    public static final String PROTOCOLO = "protocolo";
    /** "Usuário" das linhas que não são de uma pessoa. */
    public static final String SISTEMA = "sistema";

    private static final SecureRandom SORTEIO = new SecureRandom();
    /** Sem 0/O e 1/I/L: o protocolo é ditado ou copiado por pessoas. */
    private static final char[] ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private ContextoDeLog() {
    }

    public static String novoProtocolo() {
        char[] codigo = new char[6];
        for (int i = 0; i < codigo.length; i++) {
            codigo[i] = ALFABETO[SORTEIO.nextInt(ALFABETO.length)];
        }
        return new String(codigo);
    }

    /** Protocolo da requisição em andamento ({@code null} fora de uma requisição). */
    public static String protocolo() {
        return MDC.get(PROTOCOLO);
    }

    public static String usuario() {
        String usuario = MDC.get(USUARIO);
        return usuario == null ? SISTEMA : usuario;
    }

    public static void definirUsuario(String login) {
        if (login == null || login.isBlank()) {
            MDC.remove(USUARIO);
        } else {
            MDC.put(USUARIO, login);
        }
    }

    /**
     * Executa com o usuário no contexto e restaura o anterior (tarefas em segundo plano que trabalham para uma
     * pessoa: monitor da pasta, gravação da planilha, conferência do relatório do RH).
     */
    public static void comUsuario(String login, Runnable tarefa) {
        String anterior = MDC.get(USUARIO);
        definirUsuario(login);
        try {
            tarefa.run();
        } finally {
            definirUsuario(anterior);
        }
    }

    /** Nome de pasta seguro para o login (só letras, números, ponto, hífen e sublinhado). */
    public static String pastaDo(String usuario) {
        if (usuario == null || usuario.isBlank()) {
            return SISTEMA;
        }
        String limpo = usuario.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        limpo = limpo.replaceAll("^\\.+", "_");
        return limpo.isEmpty() ? SISTEMA : limpo.length() > 60 ? limpo.substring(0, 60) : limpo;
    }
}
