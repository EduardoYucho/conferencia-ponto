package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.AbrangenciaFeriado;
import br.com.conferenciaponto.domain.model.Feriado;

import java.time.LocalDate;

public record FeriadoResponse(LocalDate data, String descricao, AbrangenciaFeriado abrangencia, String abrangenciaRotulo) {

    public static FeriadoResponse de(Feriado f) {
        return new FeriadoResponse(f.data(), f.descricao(), f.abrangencia(), f.abrangencia().rotulo());
    }
}
