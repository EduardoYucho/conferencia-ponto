package br.com.conferenciaponto.infrastructure.log;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * As últimas linhas de log em memória, para a tela "Logs" do administrador acompanhar ao vivo sem ler os
 * arquivos. Guarda um número fixo de linhas (as mais antigas saem); cada linha tem um número de sequência, e a
 * tela pede "o que veio depois da sequência N".
 */
public final class RegistroDeLogs {

    public static final int CAPACIDADE = 3000;
    private static final int TAMANHO_MENSAGEM = 2000;
    private static final int TAMANHO_ERRO = 6000;

    /**
     * @param nivel  ERROR, WARN, INFO, DEBUG ou TRACE
     * @param origem classe que escreveu a linha (nome curto)
     * @param erro   pilha da exceção, se houve (cortada)
     */
    public record Linha(long seq, Instant quando, String nivel, String usuario, String protocolo, String origem,
                        String mensagem, String erro) {
    }

    private static final Deque<Linha> LINHAS = new ArrayDeque<>(CAPACIDADE);
    private static final AtomicLong SEQUENCIA = new AtomicLong();

    private RegistroDeLogs() {
    }

    public static void registrar(Instant quando, String nivel, String usuario, String protocolo, String origem,
                                 String mensagem, String erro) {
        Linha linha = new Linha(SEQUENCIA.incrementAndGet(), quando, nivel,
                usuario == null || usuario.isBlank() ? ContextoDeLog.SISTEMA : usuario, protocolo, origem,
                cortar(mensagem, TAMANHO_MENSAGEM), cortar(erro, TAMANHO_ERRO));
        synchronized (LINHAS) {
            if (LINHAS.size() >= CAPACIDADE) {
                LINHAS.removeFirst();
            }
            LINHAS.addLast(linha);
        }
    }

    /**
     * Linhas depois da sequência informada, da mais antiga para a mais nova, no máximo {@code limite} (as mais
     * recentes).
     *
     * @param nivelMinimo ERROR (só erros), WARN (erros e avisos), INFO ou DEBUG; nulo = tudo
     * @param usuario     só as linhas deste usuário; nulo = de todos
     * @param texto       trecho que precisa aparecer na mensagem, no protocolo ou na origem; nulo = qualquer
     */
    public static List<Linha> depoisDe(long sequencia, String nivelMinimo, String usuario, String texto, int limite) {
        return depoisDe(sequencia, nivelMinimo, usuario, texto, limite, false);
    }

    /**
     * @param semConsultas deixa de fora as linhas de acesso de simples leitura que deram certo ("GET ... -> 200"):
     *                     sobra o que muda alguma coisa e tudo o que foi recusado ou falhou
     */
    public static List<Linha> depoisDe(long sequencia, String nivelMinimo, String usuario, String texto, int limite,
                                       boolean semConsultas) {
        int corte = peso(nivelMinimo);
        String procurado = texto == null || texto.isBlank() ? null : texto.strip().toLowerCase(Locale.ROOT);
        List<Linha> achadas = new ArrayList<>();
        synchronized (LINHAS) {
            for (Linha l : LINHAS) {
                if (l.seq() > sequencia && peso(l.nivel()) >= corte
                        && (usuario == null || usuario.isBlank() || usuario.equalsIgnoreCase(l.usuario()))
                        && (procurado == null || contem(l, procurado))
                        && !(semConsultas && consultaComSucesso(l))) {
                    achadas.add(l);
                }
            }
        }
        int maximo = Math.max(1, limite);
        return achadas.size() <= maximo ? achadas : achadas.subList(achadas.size() - maximo, achadas.size());
    }

    public static long ultimaSequencia() {
        return SEQUENCIA.get();
    }

    /** Só para os testes. */
    static void limpar() {
        synchronized (LINHAS) {
            LINHAS.clear();
        }
    }

    private static boolean consultaComSucesso(Linha l) {
        return "acesso".equals(l.origem()) && "INFO".equals(l.nivel()) && l.mensagem() != null
                && l.mensagem().startsWith("GET ");
    }

    private static boolean contem(Linha l, String procurado) {
        return (l.mensagem() != null && l.mensagem().toLowerCase(Locale.ROOT).contains(procurado))
                || (l.protocolo() != null && l.protocolo().toLowerCase(Locale.ROOT).contains(procurado))
                || (l.origem() != null && l.origem().toLowerCase(Locale.ROOT).contains(procurado))
                || (l.erro() != null && l.erro().toLowerCase(Locale.ROOT).contains(procurado));
    }

    private static int peso(String nivel) {
        if (nivel == null) {
            return 0;
        }
        return switch (nivel.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> 40;
            case "WARN" -> 30;
            case "INFO" -> 20;
            case "DEBUG" -> 10;
            default -> 0;
        };
    }

    private static String cortar(String texto, int maximo) {
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        return texto.length() <= maximo ? texto : texto.substring(0, maximo) + "… (cortado)";
    }
}
