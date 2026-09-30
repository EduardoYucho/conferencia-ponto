package br.com.conferenciaponto.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;

/**
 * Banco de horas (prefixo {@code ponto.banco-horas}).
 *
 * @param inicioPrimeiroCiclo início do ciclo criado na primeira subida (o dia seguinte ao último
 *                            zeramento do RH); depois, o período é corrigido pela tela
 * @param duracaoMeses        duração prevista de cada ciclo
 * @param cronAlertas         horário da verificação diária dos prazos (padrão 01:00)
 */
@ConfigurationProperties(prefix = "ponto.banco-horas")
public record BancoHorasProperties(
        @DefaultValue("2026-05-25") LocalDate inicioPrimeiroCiclo,
        @DefaultValue("6") int duracaoMeses,
        @DefaultValue("0 0 1 * * *") String cronAlertas) {
}
