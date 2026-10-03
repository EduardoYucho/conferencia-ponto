package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.ConsultarConciliacaoUseCase.Textos;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Relatório do RH enviado.
 *
 * @param ultimoDiaConferido  fim do período ou véspera da emissão (o que vier antes)
 * @param saldoRhSegundos     soma do saldo do RH nos dias conferidos (null na resposta do envio)
 * @param saldoLocalSegundos  soma do saldo da conferência nas mesmas datas
 * @param diasDiferentes      dias do relatório que continuam diferentes do sistema (para decidir ou mantidos)
 * @param tela                o relatório em palavras: período, situação, saldos e diferença já escritos
 */
public record RelatorioRhResponse(UUID id, String nomeArquivo, String funcionario, LocalDateTime emitidoEm,
                                  LocalDate periodoInicio, LocalDate periodoFim, LocalDate ultimoDiaConferido,
                                  Integer totalPrevistoSegundos, Integer totalTrabalhadoSegundos,
                                  Integer totalSaldoSegundos, int diasLidos, StatusRelatorioRh status, String mensagem,
                                  int divergencias, Instant enviadoEm, String enviadoPor, Instant processadoEm,
                                  Integer diasConferidos, Integer saldoRhSegundos, Integer saldoLocalSegundos,
                                  Integer diasEmAbertoLocal, Integer pendentes, Integer diasDiferentes,
                                  Textos.Relatorio tela) {

    public static RelatorioRhResponse de(RelatorioRh r) {
        return de(r, null);
    }

    public static RelatorioRhResponse de(ConciliacaoResumoView.Comparativo c) {
        return de(c.relatorio(), c);
    }

    private static RelatorioRhResponse de(RelatorioRh r, ConciliacaoResumoView.Comparativo c) {
        return new RelatorioRhResponse(r.id(), r.nomeArquivo(), r.funcionario(), r.emitidoEm(), r.periodoInicio(),
                r.periodoFim(), r.ultimoDiaConferido(), r.totalPrevistoSegundos(), r.totalTrabalhadoSegundos(),
                r.totalSaldoSegundos(), r.diasLidos(), r.status(), r.mensagem(), r.divergencias(), r.enviadoEm(),
                r.enviadoPor(), r.processadoEm(),
                c == null ? null : c.diasConferidos(), c == null ? null : c.saldoRhSegundos(),
                c == null ? null : c.saldoLocalSegundos(), c == null ? null : c.diasEmAbertoLocal(),
                c == null ? null : c.pendentes(), c == null ? null : c.diasDiferentes(), Textos.relatorio(r, c));
    }
}
