package br.com.conferenciaponto.modulos.backup.domain;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Nomes de pasta e caminhos que vêm de fora, tratados antes de virar arquivo no Windows. */
public final class NomesSeguros {

    private static final Pattern PROIBIDOS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
    private static final Set<String> RESERVADOS = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private NomesSeguros() {
    }

    /** Nome de cliente para nome de pasta: troca o que o Windows não aceita por "_". */
    public static String pastaDoCliente(String nome) {
        String limpo = PROIBIDOS.matcher(nome == null ? "" : nome.trim()).replaceAll("_");
        limpo = limpo.replaceAll("[. ]+$", "");
        if (limpo.isEmpty()) {
            limpo = "cliente";
        }
        if (RESERVADOS.contains(limpo.toUpperCase(Locale.ROOT))) {
            limpo = "_" + limpo;
        }
        return limpo.length() > 80 ? limpo.substring(0, 80) : limpo;
    }

    /**
     * Caminho de uma entrada do .tar resolvido dentro de {@code destino}, ou vazio se for perigoso (absoluto, com
     * "..", com letra de unidade, com nome reservado ou com caractere que o Windows não aceita).
     */
    public static Optional<Path> dentroDe(Path destino, String caminhoDaEntrada) {
        if (caminhoDaEntrada == null || caminhoDaEntrada.isEmpty() || caminhoDaEntrada.startsWith("/")
                || caminhoDaEntrada.startsWith("\\")) {
            return Optional.empty();
        }
        Path resultado = destino;
        for (String parte : caminhoDaEntrada.split("/")) {
            if (parte.isEmpty()) {
                continue;
            }
            if (parte.equals(".") || parte.equals("..") || PROIBIDOS.matcher(parte).find()
                    || parte.endsWith(".") || parte.endsWith(" ")) {
                return Optional.empty();
            }
            String base = parte.contains(".") ? parte.substring(0, parte.indexOf('.')) : parte;
            if (RESERVADOS.contains(base.toUpperCase(Locale.ROOT))) {
                return Optional.empty();
            }
            resultado = resultado.resolve(parte);
        }
        Path normalizado = resultado.normalize();
        if (!normalizado.startsWith(destino.normalize()) || normalizado.equals(destino.normalize())) {
            return Optional.empty();
        }
        return Optional.of(normalizado);
    }
}
