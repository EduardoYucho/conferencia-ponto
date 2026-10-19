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
 * grade = sem expediente, como sábado e domingo no horário padrão), a tolerância por marcação e a carga semanal
 * que a pessoa deve cumprir.
 *
 * <p>Mudar o horário cria uma nova vigência: os dias anteriores continuam calculados com o horário que valia
 * para eles.
 *
 * <p>A carga semanal informada ({@code cargaSemanalInformadaSegundos}; vazia = a soma do horário) pode ser maior
 * que o horário (ex.: horário de 40h, carga de 42h): a diferença é dividida pelos dias com expediente e entra no
 * banco de horas em cada dia útil fechado, e as horas do fim de semana compensam. O cálculo de cada dia (saldo
 * contra a grade, como o RH) não muda.
 */
public record HorarioTrabalho(UUID id, UUID usuarioId, LocalDate vigenteDesde, int toleranciaMinutos,
                              Map<DayOfWeek, GradeHoraria> dias, Instant criadoEm, String criadoPor,
                              Integer cargaSemanalInformadaSegundos) {

    /** Vigência usada pelo horário inicial de cada usuário (vale para todo o histórico). */
    public static final LocalDate DESDE_SEMPRE = LocalDate.of(2000, 1, 1);

    /** Carga semanal mínima e máxima que dá para informar (1 h e 80 h). */
    public static final int CARGA_MINIMA_SEGUNDOS = 3600;
    public static final int CARGA_MAXIMA_SEGUNDOS = 80 * 3600;

    /** Sem carga semanal informada (vale a soma do horário). */
    public HorarioTrabalho(UUID id, UUID usuarioId, LocalDate vigenteDesde, int toleranciaMinutos,
                           Map<DayOfWeek, GradeHoraria> dias, Instant criadoEm, String criadoPor) {
        this(id, usuarioId, vigenteDesde, toleranciaMinutos, dias, criadoEm, criadoPor, null);
    }

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
        if (cargaSemanalInformadaSegundos != null
                && (cargaSemanalInformadaSegundos < CARGA_MINIMA_SEGUNDOS || cargaSemanalInformadaSegundos > CARGA_MAXIMA_SEGUNDOS)) {
            throw new RegraNegocioException("HORARIO_CARGA_SEMANAL",
                    "A carga semanal deve ficar entre 1 e 80 horas.");
        }
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

    /** O que o horário soma na semana (os dias com expediente). */
    public int somaSemanalSegundos() {
        return dias.values().stream().mapToInt(GradeHoraria::cargaHorariaSegundos).sum();
    }

    /** A carga semanal que vale: a informada ou, sem ela, a soma do horário. */
    public int cargaSemanalSegundos() {
        return cargaSemanalInformadaSegundos != null ? cargaSemanalInformadaSegundos : somaSemanalSegundos();
    }

    /** Quanto a carga passa do horário por semana (positivo = falta horário para fechar a carga). */
    public int diferencaSemanalSegundos() {
        return cargaSemanalSegundos() - somaSemanalSegundos();
    }

    public int diasComExpediente() {
        return dias.size();
    }

    /**
     * A diferença da semana dividida pelos dias com expediente (arredondada ao segundo): é o que cada dia útil
     * fechado leva ao banco (positivo = débito; negativo = o horário passa da carga, crédito).
     */
    public int diferencaPorDiaSegundos() {
        int diferenca = diferencaSemanalSegundos();
        return diferenca == 0 ? 0 : (int) Math.round((double) diferenca / dias.size());
    }

    /** O mesmo horário, com outra carga semanal (vazia = a soma do horário). */
    public HorarioTrabalho comCarga(Integer cargaSegundos) {
        return new HorarioTrabalho(id, usuarioId, vigenteDesde, toleranciaMinutos, dias, criadoEm, criadoPor, cargaSegundos);
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
