package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.MesJornadaView;

import java.time.LocalDate;
import java.util.List;

/** @param conferencia a conferência do mês (null = não conferido): o painel avisa que o mês está travado */
public record MesJornadaResponse(int ano, int mes, List<RegistroJornadaResponse> dias, ResumoMensalResponse resumo,
                                 List<AusenciaResponse> ausencias, List<FeriadoResponse> feriados,
                                 List<LancamentoBancoResponse> lancamentos, List<Expediente> expedientes,
                                 ConferenciaResponse conferencia) {

    /** Expediente previsto do dia pelo horário do usuário (dias sem expediente não aparecem). */
    public record Expediente(LocalDate data, int previstoSegundos, List<ConfiguracaoResponse.Periodo> periodos) {
    }

    public static MesJornadaResponse de(MesJornadaView v) {
        return de(v, null);
    }

    public static MesJornadaResponse de(MesJornadaView v, ConferenciaResponse conferencia) {
        return new MesJornadaResponse(
                v.referencia().getYear(),
                v.referencia().getMonthValue(),
                v.dias().stream().map(RegistroJornadaResponse::de).toList(),
                ResumoMensalResponse.de(v.resumo()),
                v.ausencias().stream().map(AusenciaResponse::de).toList(),
                v.feriados().stream().map(FeriadoResponse::de).toList(),
                v.lancamentos().stream().map(LancamentoBancoResponse::de).toList(),
                v.expedientes().stream().map(e -> new Expediente(e.data(), e.previstoSegundos(),
                        ConfiguracaoResponse.Periodo.de(e.periodos()))).toList(),
                conferencia);
    }
}
