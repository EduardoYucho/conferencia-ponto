package br.com.expedio.application;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Parâmetros do banco de horas (vêm do application.yml, prefixo {@code expedio.banco-horas}).
 *
 * @param inicioPrimeiroCiclo início do ciclo criado na primeira subida (depois disso, vale o que está no banco)
 * @param duracaoMeses        duração prevista de cada ciclo (o RH zera o banco a cada 6 meses)
 */
public record ParametrosBancoHoras(LocalDate inicioPrimeiroCiclo, int duracaoMeses) {

    public ParametrosBancoHoras {
        Objects.requireNonNull(inicioPrimeiroCiclo, "inicioPrimeiroCiclo");
        if (duracaoMeses < 1 || duracaoMeses > 24) {
            throw new IllegalArgumentException("expedio.banco-horas.duracao-meses deve estar entre 1 e 24");
        }
    }
}
