package br.com.conferenciaponto.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Parâmetros externos (application.yml, prefixo {@code ponto}).
 * Horários como texto "HH:mm" para não depender de formatação por locale.
 */
@ConfigurationProperties(prefix = "ponto")
public record PontoProperties(
        @DefaultValue("America/Sao_Paulo") String fusoHorario,
        @DefaultValue("5") int toleranciaMinutos,
        @DefaultValue Grade grade,
        @DefaultValue("http://localhost:5173") List<String> corsOrigensPermitidas) {

    public record Grade(
            @DefaultValue("08:00") String entrada1,
            @DefaultValue("12:00") String saida1,
            @DefaultValue("13:00") String entrada2,
            @DefaultValue("17:48") String saida2) {
    }
}
