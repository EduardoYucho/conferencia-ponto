package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.application.view.MesJornadaView;

import java.util.List;

public record MesJornadaResponse(int ano, int mes, List<RegistroJornadaResponse> dias, ResumoMensalResponse resumo,
                                 List<AusenciaResponse> ausencias, List<Feriado> feriados) {

    public static MesJornadaResponse de(MesJornadaView v) {
        return new MesJornadaResponse(
                v.referencia().getYear(),
                v.referencia().getMonthValue(),
                v.dias().stream().map(RegistroJornadaResponse::de).toList(),
                ResumoMensalResponse.de(v.resumo()),
                v.ausencias().stream().map(AusenciaResponse::de).toList(),
                v.feriados());
    }
}
