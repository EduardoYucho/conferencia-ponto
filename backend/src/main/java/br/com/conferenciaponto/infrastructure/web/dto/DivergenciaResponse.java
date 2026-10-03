package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.DivergenciaView;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Diferença com o RH: de um lado o sistema como está agora, do outro o relatório do RH.
 *
 * @param local registro atual do dia no sistema (null = sem registro)
 * @param rh    o dia no relatório vigente do RH
 * @param tela  a diferença pronta para a tela "Conferir com o RH": a frase do que difere, o que cada lado tem,
 *              o tamanho da diferença e os botões que aparecem (a tela só mostra)
 */
public record DivergenciaResponse(UUID id, LocalDate data, TipoDivergencia tipo, String tipoRotulo, String descricao,
                                  boolean aceitavel, String motivoNaoAceitavel, StatusDivergencia status,
                                  Instant detectadaEm, Instant resolvidaEm, String resolvidaPor, String observacao,
                                  TipoDia tipoDiaLocal, RegistroJornadaResponse local, DiaRh rh,
                                  DivergenciaView.Tela tela) {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    public record DiaRh(List<String> horarios, String ocorrencia, int jornadaPrevistaSegundos, int segundosTrabalhados,
                        int saldoSegundos, UUID relatorioId, LocalDateTime relatorioEmitidoEm, String relatorioArquivo) {
    }

    public static DivergenciaResponse de(DivergenciaView v) {
        Divergencia d = v.divergencia();
        DiaRelatorioRh r = v.rh();
        DiaRh rh = r == null ? null : new DiaRh(r.horarios().stream().map(HORA::format).toList(), r.ocorrencia(),
                r.jornadaPrevistaSegundos(), r.segundosTrabalhados(), r.saldoSegundos(),
                v.relatorio() == null ? null : v.relatorio().id(),
                v.relatorio() == null ? null : v.relatorio().emitidoEm(),
                v.relatorio() == null ? null : v.relatorio().nomeArquivo());
        return new DivergenciaResponse(d.id(), d.data(), d.tipo(), d.tipo().rotulo(), d.descricao(), d.aceitavel(),
                d.motivoNaoAceitavel(), d.status(), d.detectadaEm(), d.resolvidaEm(), d.resolvidaPor(), d.observacao(),
                v.tipoDiaLocal(), v.local() == null ? null : RegistroJornadaResponse.de(v.local()), rh, v.tela());
    }
}
