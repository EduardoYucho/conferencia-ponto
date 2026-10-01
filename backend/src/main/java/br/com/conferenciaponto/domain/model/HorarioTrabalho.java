package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Horário de trabalho de um usuário a partir de uma data (vigência): a grade de cada dia da semana (dia sem
 * grade = sem expediente, como sábado e domingo no horário padrão) e a tolerância por marcação.
 *
 * <p>Mudar o horário cria uma nova vigência: os dias anteriores continuam calculados com o horário que valia
 * para eles.
 */
public record HorarioTrabalho(UUID id, UUID usuarioId, LocalDate vigenteDesde, int toleranciaMinutos,
                              Map<DayOfWeek, GradeHoraria> dias, Instant criadoEm, String criadoPor) {

    /** Vigência usada pelo horário inicial de cada usuário (vale para todo o histórico). */
    public static final LocalDate DESDE_SEMPRE = LocalDate.of(2000, 1, 1);

    public HorarioTrabalho {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(criadoEm, "criadoEm");
        if (vigenteDesde == null) {
            throw new RegraNegocioException("HORARIO_SEM_VIGENCIA", "Informe a partir de quando o horário vale.");
        }
        if (toleranciaMinutos < 0 || toleranciaMinutos > 60) {
            throw new RegraNegocioException("HORARIO_TOLERANCIA", "A tolerância deve ficar entre 0 e 60 minutos.");
        }
        EnumMap<DayOfWeek, GradeHoraria> copia = new EnumMap<>(DayOfWeek.class);
        if (dias != null) {
            dias.forEach((dia, grade) -> {
                if (dia != null && grade != null) {
                    copia.put(dia, grade);
                }
            });
        }
        if (copia.isEmpty()) {
            throw new RegraNegocioException("HORARIO_SEM_EXPEDIENTE", "Informe o horário de pelo menos um dia da semana.");
        }
        dias = Collections.unmodifiableMap(copia);
    }

    /** Segunda a sexta com a mesma grade; sábado e domingo sem expediente. */
    public static HorarioTrabalho semanal(UUID usuarioId, LocalDate vigenteDesde, GradeHoraria grade,
                                          int toleranciaMinutos, String criadoPor, Instant agora) {
        EnumMap<DayOfWeek, GradeHoraria> dias = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
                dias.put(d, grade);
            }
        }
        return new HorarioTrabalho(UUID.randomUUID(), usuarioId, vigenteDesde, toleranciaMinutos, dias, agora, criadoPor);
    }

    public Optional<GradeHoraria> gradeDo(LocalDate data) {
        return Optional.ofNullable(dias.get(data.getDayOfWeek()));
    }

    public boolean temExpediente(LocalDate data) {
        return dias.containsKey(data.getDayOfWeek());
    }

    /** Carga prevista para a data pelo horário (sem considerar feriados e ausências). */
    public int previstoSegundos(LocalDate data) {
        return gradeDo(data).map(GradeHoraria::cargaHorariaSegundos).orElse(0);
    }

    /**
     * Motor de cálculo para a data: a grade do dia da semana e a tolerância deste horário. Em dia sem
     * expediente a grade não é usada (todo o tempo é crédito), mas o motor precisa de uma: vale a do
     * primeiro dia com expediente.
     */
    public MotorCalculoJornadaService motor(LocalDate data) {
        GradeHoraria grade = gradeDo(data).orElseGet(() -> dias.values().iterator().next());
        return new MotorCalculoJornadaService(grade, Duration.ofMinutes(toleranciaMinutos));
    }
}
