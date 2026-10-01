package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoDia;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RegistroJornadaResponse(
        UUID id,
        LocalDate data,
        TipoDia tipoDia,
        boolean registroManual,
        boolean ajustado,
        StatusJornada status,
        List<MarcacaoResponse> batidas,
        int jornadaPrevistaSegundos,
        int segundosTrabalhados,
        Integer saldoDiarioSegundos,
        List<ConfiguracaoResponse.Periodo> grade) {

    public static RegistroJornadaResponse de(RegistroJornadaView v) {
        return new RegistroJornadaResponse(
                v.id(),
                v.data(),
                v.tipoDia(),
                v.registroManual(),
                !v.horariosAjustados().isEmpty(),
                v.status(),
                v.marcacoes().stream()
                        .map(m -> MarcacaoResponse.de(m, m.real() != null && v.horariosAjustados().contains(m.real())))
                        .toList(),
                v.jornadaPrevistaSegundos(),
                v.segundosTrabalhados(),
                v.saldoDiarioSegundos(),
                ConfiguracaoResponse.Periodo.de(v.grade()));
    }
}
