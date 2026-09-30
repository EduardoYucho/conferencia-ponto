package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.TipoAusencia;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record AusenciaResponse(UUID id, LocalDate dataInicio, LocalDate dataFim, TipoAusencia tipo, String tipoRotulo,
                               String descricao, long dias, Instant criadoEm, String criadoPor) {

    public static AusenciaResponse de(Ausencia a) {
        return new AusenciaResponse(a.id(), a.dataInicio(), a.dataFim(), a.tipo(), a.tipo().rotulo(), a.descricao(),
                ChronoUnit.DAYS.between(a.dataInicio(), a.dataFim()) + 1, a.criadoEm(), a.criadoPor());
    }
}
