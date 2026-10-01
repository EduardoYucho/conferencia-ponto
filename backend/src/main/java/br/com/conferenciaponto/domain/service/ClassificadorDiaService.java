package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.CalendarioAusencias;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Classifica uma data de um usuário em UTIL, FIM_DE_SEMANA (dia sem expediente no horário dele), FERIADO ou
 * AUSENCIA. Precedência: feriado, dia sem expediente e, por último, ausência (férias/atestado/licença/folga
 * só mudam dias que seriam úteis — todos têm jornada base zero).
 */
public final class ClassificadorDiaService {

    private final CalendarioFeriados calendario;
    private final CalendarioAusencias ausencias;

    public ClassificadorDiaService(CalendarioFeriados calendario) {
        this(calendario, CalendarioAusencias.NENHUMA);
    }

    public ClassificadorDiaService(CalendarioFeriados calendario, CalendarioAusencias ausencias) {
        this.calendario = Objects.requireNonNull(calendario, "calendario");
        this.ausencias = Objects.requireNonNull(ausencias, "ausencias");
    }

    /** Pelo horário do usuário vigente na data. */
    public TipoDia classificar(UUID usuarioId, LocalDate data, HorarioTrabalho horario) {
        return classificar(usuarioId, data, horario.temExpediente(data));
    }

    /**
     * @param expediente o horário do usuário prevê trabalho nesse dia da semana
     */
    public TipoDia classificar(UUID usuarioId, LocalDate data, boolean expediente) {
        Objects.requireNonNull(data, "data");
        if (calendario.isFeriado(data)) {
            return TipoDia.FERIADO;
        }
        if (!expediente) {
            return TipoDia.FIM_DE_SEMANA;
        }
        return ausencias.isAusencia(usuarioId, data) ? TipoDia.AUSENCIA : TipoDia.UTIL;
    }
}
