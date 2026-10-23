package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.ConferenciaMes;

import java.time.Instant;
import java.util.UUID;

/** Uma conferência do mês: quem, quando, os números da hora e, se foi reaberta, quem reabriu e por quê. */
public record ConferenciaResponse(UUID id, int ano, int mes, Instant conferidoEm, String conferidoPor,
                                  String observacao, int saldoMesSegundos, int trabalhadoSegundos, int diasRegistrados,
                                  Instant reabertoEm, String reabertoPor, String motivoReabertura) {

    public static ConferenciaResponse de(ConferenciaMes c) {
        return new ConferenciaResponse(c.id(), c.mes().getYear(), c.mes().getMonthValue(), c.conferidoEm(),
                c.conferidoPor(), c.observacao(), c.saldoMesSegundos(), c.trabalhadoSegundos(), c.diasRegistrados(),
                c.reabertoEm(), c.reabertoPor(), c.motivoReabertura());
    }
}
