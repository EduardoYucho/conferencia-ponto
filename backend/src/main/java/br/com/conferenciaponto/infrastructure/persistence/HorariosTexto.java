package br.com.conferenciaponto.infrastructure.persistence;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Lista de horários gravada como texto: {@code "08:05:53 12:00:00 18:03:22"}. */
final class HorariosTexto {

    private HorariosTexto() {
    }

    static List<LocalTime> ler(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        return Arrays.stream(texto.trim().split("\\s+")).map(LocalTime::parse).toList();
    }

    static String escrever(Collection<LocalTime> horarios) {
        return horarios.stream().map(LocalTime::toString).map(HorariosTexto::comSegundos)
                .collect(Collectors.joining(" "));
    }

    static String escreverOuNulo(Collection<LocalTime> horarios) {
        return horarios == null || horarios.isEmpty() ? null : escrever(horarios);
    }

    /** {@code LocalTime.toString()} omite ":00" nos segundos; aqui o formato é sempre HH:mm:ss. */
    private static String comSegundos(String horario) {
        return horario.length() == 5 ? horario + ":00" : horario;
    }
}
