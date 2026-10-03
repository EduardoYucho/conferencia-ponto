package br.com.conferenciaponto.infrastructure.log;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Logs da aplicação (prefixo {@code ponto.logs}).
 *
 * @param diretorio pasta dos logs (padrão: ~/.conferencia-ponto/logs); os arquivos por usuário e por hora ficam
 *                  em {@code usuarios/<login>/<aaaa-mm-dd>/<hh>h.log}
 * @param dias      por quantos dias os arquivos por usuário são guardados (os mais antigos são apagados)
 */
@ConfigurationProperties(prefix = "ponto.logs")
public record LogsProperties(String diretorio, @DefaultValue("30") int dias) {

    public Path raiz() {
        if (diretorio == null || diretorio.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".conferencia-ponto", "logs");
        }
        return Path.of(diretorio).toAbsolutePath().normalize();
    }

    public Path pastaDosUsuarios() {
        return raiz().resolve("usuarios");
    }
}
