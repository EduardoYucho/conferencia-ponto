package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.BancoDeHorasPorMes;
import br.com.conferenciaponto.application.view.AuditoriaMesView;
import br.com.conferenciaponto.domain.model.CicloBanco;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * @param bancoDeHoras o banco de horas no fim do mês, pelo ciclo que cobre o mês (o mesmo número do painel no mês
 *                     corrente); null quando o mês está fora de todos os ciclos
 */
public record AuditoriaMesResponse(int ano, int mes, List<DiaAuditoriaResponse> dias, ResumoMensalResponse resumo,
                                   List<AusenciaResponse> ausencias, List<LancamentoBancoResponse> lancamentos,
                                   BancoDeHorasResponse bancoDeHoras) {

    /**
     * @param cicloFim a data de fechamento do ciclo encerrado, ou a prevista do ciclo aberto
     */
    public record BancoDeHorasResponse(int saldoSegundos, LocalDate cicloInicio, LocalDate cicloFim,
                                       boolean cicloAberto) {

        static BancoDeHorasResponse de(BancoDeHorasPorMes.NoMes banco) {
            CicloBanco c = banco.ciclo().ciclo();
            return new BancoDeHorasResponse(banco.saldoSegundos(), c.dataInicio(),
                    c.isAberto() ? c.dataFimPrevista() : c.dataFim(), c.isAberto());
        }
    }

    /** Dia com batidas (real x considerado x tolerância), saldo e comprovantes para download. */
    public record DiaAuditoriaResponse(RegistroJornadaResponse registro, List<ComprovanteArquivoResponse> comprovantes,
                                       List<AjusteResponse> ajustes) {
    }

    public static AuditoriaMesResponse de(AuditoriaMesView v, Optional<BancoDeHorasPorMes.NoMes> banco) {
        return new AuditoriaMesResponse(
                v.referencia().getYear(),
                v.referencia().getMonthValue(),
                v.dias().stream()
                        .map(d -> new DiaAuditoriaResponse(RegistroJornadaResponse.de(d.registro()),
                                d.comprovantes().stream().map(ComprovanteArquivoResponse::de).toList(),
                                d.ajustes().stream().map(AjusteResponse::de).toList()))
                        .toList(),
                ResumoMensalResponse.de(v.resumo()),
                v.ausencias().stream().map(AusenciaResponse::de).toList(),
                v.lancamentos().stream().map(LancamentoBancoResponse::de).toList(),
                banco.map(BancoDeHorasResponse::de).orElse(null));
    }
}
