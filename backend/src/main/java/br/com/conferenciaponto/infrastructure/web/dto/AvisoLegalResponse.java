package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.AvisoLegal;

import java.time.LocalDate;
import java.util.List;

/** Um aviso da lei sobre o horário ou os dias trabalhados (ALERTA, AVISO ou INFO). */
public record AvisoLegalResponse(AvisoLegal.Nivel nivel, String codigo, LocalDate data, String mensagem,
                                 String fundamento) {

    public static AvisoLegalResponse de(AvisoLegal a) {
        return new AvisoLegalResponse(a.nivel(), a.codigo(), a.data(), a.mensagem(), a.fundamento());
    }

    public static List<AvisoLegalResponse> de(List<AvisoLegal> avisos) {
        return avisos.stream().map(AvisoLegalResponse::de).toList();
    }
}
