package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.MarcacaoApurada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;

public record MarcacaoResponse(
        TipoBatida tipo,
        String rotulo,
        @JsonFormat(pattern = "HH:mm") LocalTime oficial,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime real,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime considerado,
        Integer desvioSegundos,
        boolean toleranciaAplicada,
        boolean ajustada) {

    /** @param ajustada a batida foi incluída ou corrigida manualmente */
    public static MarcacaoResponse de(MarcacaoApurada m, boolean ajustada) {
        return new MarcacaoResponse(m.tipo(), m.tipo().rotulo(), m.oficial(), m.real(),
                m.considerado(), m.desvioSegundos(), m.toleranciaAplicada(), ajustada);
    }
}
