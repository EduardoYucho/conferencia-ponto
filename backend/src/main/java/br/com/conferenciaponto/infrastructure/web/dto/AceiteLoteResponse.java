package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.ResolverDivergenciaUseCase;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.time.LocalDate;
import java.util.List;

public record AceiteLoteResponse(int aceitas, List<Falha> falhas) {

    public record Falha(LocalDate data, TipoDivergencia tipo, String mensagem) {
    }

    public static AceiteLoteResponse de(ResolverDivergenciaUseCase.ResultadoLote r) {
        return new AceiteLoteResponse(r.aceitas(),
                r.falhas().stream().map(f -> new Falha(f.data(), f.tipo(), f.mensagem())).toList());
    }
}
