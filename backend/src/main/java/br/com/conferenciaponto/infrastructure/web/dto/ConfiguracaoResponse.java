package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.GradeHoraria;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Parâmetros da jornada do usuário (horário que vale hoje) + data/hora do servidor (referência para o front-end).
 *
 * @param grade      dois primeiros períodos da grade de hoje (ou do primeiro dia com expediente), no formato antigo
 * @param periodos   todos os períodos dessa grade
 * @param horario    horário semanal vigente
 */
public record ConfiguracaoResponse(
        Grade grade,
        List<Periodo> periodos,
        int jornadaBaseSegundos,
        long toleranciaMinutos,
        String fusoHorario,
        LocalDate hoje,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime agora,
        HorarioResponse horario) {

    public record Grade(
            @JsonFormat(pattern = "HH:mm") LocalTime entrada1,
            @JsonFormat(pattern = "HH:mm") LocalTime saida1,
            @JsonFormat(pattern = "HH:mm") LocalTime entrada2,
            @JsonFormat(pattern = "HH:mm") LocalTime saida2) {

        public static Grade de(GradeHoraria g) {
            return new Grade(g.entrada1(), g.saida1(), g.entrada2(), g.saida2());
        }
    }

    public record Periodo(@JsonFormat(pattern = "HH:mm") LocalTime entrada,
                          @JsonFormat(pattern = "HH:mm") LocalTime saida) {

        public static List<Periodo> de(List<GradeHoraria.Periodo> periodos) {
            return periodos.stream().map(p -> new Periodo(p.entrada(), p.saida())).toList();
        }
    }
}
