package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.CalendarioAusencias;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Classifica uma data em UTIL, FIM_DE_SEMANA, FERIADO ou AUSENCIA.
 * Precedência: feriado, fim de semana e, por último, ausência (férias/atestado/licença/folga só
 * mudam dias que seriam úteis — todos têm jornada base zero).
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

    public TipoDia classificar(LocalDate data) {
        Objects.requireNonNull(data, "data");
        if (calendario.isFeriado(data)) {
            return TipoDia.FERIADO;
        }
        DayOfWeek diaSemana = data.getDayOfWeek();
        if (diaSemana == DayOfWeek.SATURDAY || diaSemana == DayOfWeek.SUNDAY) {
            return TipoDia.FIM_DE_SEMANA;
        }
        return ausencias.isAusencia(data) ? TipoDia.AUSENCIA : TipoDia.UTIL;
    }
}
