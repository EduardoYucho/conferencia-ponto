package br.com.conferenciaponto.domain.model;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Grade horária oficial de um dia útil.
 * A carga horária base é derivada da própria grade (padrão: 240 + 288 = 528 minutos).
 */
public record GradeHoraria(LocalTime entrada1, LocalTime saida1, LocalTime entrada2, LocalTime saida2) {

    public static final GradeHoraria PADRAO = new GradeHoraria(
            LocalTime.of(8, 0),
            LocalTime.of(12, 0),
            LocalTime.of(13, 0),
            LocalTime.of(17, 48));

    public GradeHoraria {
        Objects.requireNonNull(entrada1, "entrada1");
        Objects.requireNonNull(saida1, "saida1");
        Objects.requireNonNull(entrada2, "entrada2");
        Objects.requireNonNull(saida2, "saida2");
        boolean ordenada = entrada1.isBefore(saida1)
                && !saida1.isAfter(entrada2)
                && entrada2.isBefore(saida2);
        if (!ordenada) {
            throw new IllegalArgumentException("Grade horária fora de ordem cronológica");
        }
    }

    /** Um horário da grade: entrada (08:00, 13:00) ou saída (12:00, 17:48). */
    public record Marca(LocalTime horario, boolean entrada) {
    }

    /** As 4 marcas da grade, em ordem. */
    public List<Marca> marcas() {
        return List.of(new Marca(entrada1, true), new Marca(saida1, false),
                new Marca(entrada2, true), new Marca(saida2, false));
    }

    /** Horário oficial da posição; posições do 3º intervalo não têm horário na grade. */
    public LocalTime horarioOficial(TipoBatida tipo) {
        return switch (tipo) {
            case ENTRADA_1 -> entrada1;
            case SAIDA_1 -> saida1;
            case ENTRADA_2 -> entrada2;
            case SAIDA_2 -> saida2;
            case ENTRADA_3, SAIDA_3 -> null;
        };
    }

    public int cargaHorariaMinutos() {
        return cargaHorariaSegundos() / 60;
    }

    /** Carga do dia útil (padrão: 240 + 288 min = 31.680 s = 08:48). */
    public int cargaHorariaSegundos() {
        return (int) (ChronoUnit.SECONDS.between(entrada1, saida1)
                + ChronoUnit.SECONDS.between(entrada2, saida2));
    }
}
