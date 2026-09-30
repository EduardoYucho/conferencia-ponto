package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.AuditoriaMesView;

import java.util.List;

public record AuditoriaMesResponse(int ano, int mes, List<DiaAuditoriaResponse> dias, ResumoMensalResponse resumo,
                                   List<AusenciaResponse> ausencias) {

    /** Dia com batidas (real x considerado x tolerância), saldo e comprovantes para download. */
    public record DiaAuditoriaResponse(RegistroJornadaResponse registro, List<ComprovanteArquivoResponse> comprovantes,
                                       List<AjusteResponse> ajustes) {
    }

    public static AuditoriaMesResponse de(AuditoriaMesView v) {
        return new AuditoriaMesResponse(
                v.referencia().getYear(),
                v.referencia().getMonthValue(),
                v.dias().stream()
                        .map(d -> new DiaAuditoriaResponse(RegistroJornadaResponse.de(d.registro()),
                                d.comprovantes().stream().map(ComprovanteArquivoResponse::de).toList(),
                                d.ajustes().stream().map(AjusteResponse::de).toList()))
                        .toList(),
                ResumoMensalResponse.de(v.resumo()),
                v.ausencias().stream().map(AusenciaResponse::de).toList());
    }
}
