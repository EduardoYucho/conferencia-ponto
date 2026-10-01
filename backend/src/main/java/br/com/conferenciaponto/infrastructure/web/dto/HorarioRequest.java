package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Novo horário a partir de uma data.
 *
 * @param dias SEG, TER, QUA, QUI, SEX, SAB, DOM -> períodos "HH:MM-HH:MM" separados por espaço (até 3);
 *             dia ausente ou vazio = sem expediente
 */
public record HorarioRequest(
        @NotNull(message = "Informe a partir de quando o horário vale.") LocalDate vigenteDesde,
        @Min(value = 0, message = "A tolerância não pode ser negativa.")
        @Max(value = 60, message = "A tolerância pode ser de no máximo 60 minutos.") int toleranciaMinutos,
        @NotNull(message = "Informe o horário dos dias da semana.") Map<String, String> dias) {

    public static final Map<DayOfWeek, String> SIGLAS = siglas();

    public Map<DayOfWeek, GradeHoraria> diasDominio() {
        EnumMap<DayOfWeek, GradeHoraria> resultado = new EnumMap<>(DayOfWeek.class);
        dias.forEach((sigla, texto) -> {
            DayOfWeek dia = SIGLAS.entrySet().stream()
                    .filter(e -> e.getValue().equals(sigla == null ? "" : sigla.strip().toUpperCase(Locale.ROOT)))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new RegraNegocioException("DIA_INVALIDO",
                            "Dia da semana inválido: \"%s\" (use SEG, TER, QUA, QUI, SEX, SAB ou DOM).".formatted(sigla)));
            try {
                GradeHoraria grade = GradeHoraria.ler(texto);
                if (grade != null) {
                    resultado.put(dia, grade);
                }
            } catch (RegraNegocioException e) {
                throw new RegraNegocioException(e.getCodigo(), "%s: %s".formatted(nome(dia), e.getMessage()));
            }
        });
        return resultado;
    }

    private static String nome(DayOfWeek dia) {
        return switch (dia) {
            case MONDAY -> "Segunda";
            case TUESDAY -> "Terça";
            case WEDNESDAY -> "Quarta";
            case THURSDAY -> "Quinta";
            case FRIDAY -> "Sexta";
            case SATURDAY -> "Sábado";
            case SUNDAY -> "Domingo";
        };
    }

    private static Map<DayOfWeek, String> siglas() {
        EnumMap<DayOfWeek, String> m = new EnumMap<>(DayOfWeek.class);
        m.put(DayOfWeek.MONDAY, "SEG");
        m.put(DayOfWeek.TUESDAY, "TER");
        m.put(DayOfWeek.WEDNESDAY, "QUA");
        m.put(DayOfWeek.THURSDAY, "QUI");
        m.put(DayOfWeek.FRIDAY, "SEX");
        m.put(DayOfWeek.SATURDAY, "SAB");
        m.put(DayOfWeek.SUNDAY, "DOM");
        return Map.copyOf(m);
    }
}
