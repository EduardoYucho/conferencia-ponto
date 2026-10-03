package br.com.conferenciaponto.infrastructure.web.dto;

import java.util.Map;
import java.util.LinkedHashMap;
import java.time.DayOfWeek;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
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
        HorarioResponse horario,
        Map<String, DiaDaSemana> semana,
        int cargaDiaInteiroSegundos,
        Limites limites) {

    /** Expediente de um dia da semana no horário vigente: os períodos e a carga, já calculados. */
    public record DiaDaSemana(List<Periodo> periodos, int cargaSegundos) {
    }

    /** Limites que as telas respeitam (os mesmos que o servidor valida). */
    public record Limites(int batidasPorDia, int intervalosPorDia, int periodosPorDia, int toleranciaMaximaMinutos) {

        public static final Limites PADRAO = new Limites(TipoBatida.MAXIMO, TipoBatida.MAXIMO / 2,
                GradeHoraria.MAXIMO_PERIODOS, 60);
    }

    /**
     * @param semana SEG..DOM → expediente do dia (só os dias com expediente)
     * @param cargaDiaInteiroSegundos a carga mais comum entre os dias com expediente ("um dia inteiro")
     */
    public static ConfiguracaoResponse de(HorarioTrabalho horario, MotorCalculoJornadaService motor, String fuso,
                                          LocalDate hoje, LocalTime agora) {
        GradeHoraria g = motor.grade();
        Map<String, DiaDaSemana> semana = new LinkedHashMap<>();
        Map<Integer, Integer> frequencia = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            GradeHoraria grade = horario.dias().get(d);
            if (grade != null) {
                semana.put(HorarioRequest.SIGLAS.get(d), new DiaDaSemana(Periodo.de(grade.periodos()),
                        grade.cargaHorariaSegundos()));
                frequencia.merge(grade.cargaHorariaSegundos(), 1, Integer::sum);
            }
        }
        int diaInteiro = frequencia.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(g.cargaHorariaSegundos());
        return new ConfiguracaoResponse(Grade.de(g), Periodo.de(g.periodos()), g.cargaHorariaSegundos(),
                motor.toleranciaMinutos(), fuso, hoje, agora, HorarioResponse.de(horario), semana, diaInteiro,
                Limites.PADRAO);
    }


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
