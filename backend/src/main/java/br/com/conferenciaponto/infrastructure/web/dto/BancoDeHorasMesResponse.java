package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.GerenciarHorariosUseCase;
import br.com.conferenciaponto.application.view.AuditoriaMesView;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView;
import br.com.conferenciaponto.application.view.ConferenciaDoMesView;

import java.time.LocalDate;
import java.util.List;

/**
 * A tela Banco de horas de um mês.
 *
 * @param horario       o horário de referência (o de hoje, no mês corrente; senão, o do fim do mês), com os avisos
 * @param dias          os dias registrados, com os comprovantes, o histórico de mudanças à mão e a diferença da carga
 * @param semExpediente os dias do mês sem expediente no horário (fim de semana)
 * @param semRegistro   os dias úteis que já passaram sem registro (não entram no banco)
 * @param avisos        o que esbarra na lei nos dias trabalhados do mês
 * @param excluidos     os dias que tinham batidas e foram excluídos (quem, quando, o que tinham)
 */
public record BancoDeHorasMesResponse(int ano, int mes, LocalDate hoje, HorarioResponse horario,
                                      CompensacaoResponse compensacao, ResumoMensalResponse resumo,
                                      AuditoriaMesResponse.BancoDeHorasResponse bancoDeHoras, List<DiaResponse> dias,
                                      List<LocalDate> semExpediente, List<LocalDate> semRegistro,
                                      List<LancamentoBancoResponse> lancamentos, List<AusenciaResponse> ausencias,
                                      List<FeriadoResponse> feriados, List<AvisoLegalResponse> avisos,
                                      ConferenciaMesResponse conferencia, List<AjusteResponse> excluidos) {

    /** Um dia: as batidas (com a marca de ajustada), os PDFs, o histórico e o que a carga levou ao banco. */
    public record DiaResponse(RegistroJornadaResponse registro, List<ComprovanteArquivoResponse> comprovantes,
                              List<AjusteResponse> ajustes, boolean semExpediente, Integer diferencaDaCargaSegundos) {
    }

    /** A compensação da carga no mês (diferenças com o sinal do banco: negativo = débito). */
    public record CompensacaoResponse(int cargaSemanalSegundos, int somaDoHorarioSegundos, int diferencaSemanalSegundos,
                                      int diferencaPorDiaSegundos, int diasComExpediente, boolean cargaInformada,
                                      int diasUteis, int diasUteisContados, int diferencaPrevistaSegundos,
                                      int diferencaContadaSegundos, int semExpedienteSegundos, int compensadoSegundos,
                                      int paraOBancoSegundos, int faltaSegundos, List<BlocoResponse> finsDeSemanaRestantes) {

        static CompensacaoResponse de(BancoDeHorasMesView.Compensacao c) {
            return new CompensacaoResponse(c.cargaSemanal(), c.somaDoHorario(), c.diferencaSemanal(), c.diferencaPorDia(),
                    c.diasComExpediente(), c.cargaInformada(), c.diasUteis(), c.diasUteisContados(), c.diferencaPrevista(),
                    c.diferencaContada(), c.semExpedienteSegundos(), c.compensado(), c.paraOBanco(), c.falta(),
                    c.finsDeSemanaRestantes().stream().map(b -> new BlocoResponse(b.inicio(), b.fim())).toList());
        }
    }

    public record BlocoResponse(LocalDate inicio, LocalDate fim) {
    }

    /**
     * A conferência do mês.
     *
     * @param mudou        os números de agora são diferentes dos da conferência
     * @param terminou     o mês já terminou (só então dá para conferir)
     * @param podeConferir quem está vendo pode conferir e reabrir
     */
    public record ConferenciaMesResponse(ConferenciaResponse vigente, boolean mudou, int saldoAgoraSegundos,
                                         int trabalhadoAgoraSegundos, int diasRegistradosAgora, boolean terminou,
                                         boolean podeConferir, List<ConferenciaResponse> historico) {

        public static ConferenciaMesResponse de(ConferenciaDoMesView c, boolean podeConferir) {
            return new ConferenciaMesResponse(c.vigente() == null ? null : ConferenciaResponse.de(c.vigente()), c.mudou(),
                    c.agora().saldoMensalSegundos(), c.agora().segundosTrabalhados(), c.agora().diasRegistrados(),
                    c.terminou(), podeConferir, c.historico().stream().map(ConferenciaResponse::de).toList());
        }
    }

    public static BancoDeHorasMesResponse de(BancoDeHorasMesView v, boolean podeConferir) {
        AuditoriaMesView a = v.auditoria();
        List<DiaResponse> dias = a.dias().stream().map(d -> new DiaResponse(RegistroJornadaResponse.de(d.registro()),
                d.comprovantes().stream().map(ComprovanteArquivoResponse::de).toList(),
                d.ajustes().stream().map(AjusteResponse::de).toList(),
                v.semExpediente().contains(d.registro().data()),
                v.diferencaDoDia().get(d.registro().data()))).toList();
        return new BancoDeHorasMesResponse(v.mes().getYear(), v.mes().getMonthValue(), v.hoje(),
                HorarioResponse.de(new GerenciarHorariosUseCase.Vigencia(v.horario(), v.avisosDoHorario())),
                CompensacaoResponse.de(v.compensacao()), ResumoMensalResponse.de(a.resumo()),
                v.banco().map(AuditoriaMesResponse.BancoDeHorasResponse::de).orElse(null), dias,
                List.copyOf(v.semExpediente()), v.semRegistro(),
                a.lancamentos().stream().map(LancamentoBancoResponse::de).toList(),
                a.ausencias().stream().map(AusenciaResponse::de).toList(),
                a.feriados().stream().map(FeriadoResponse::de).toList(),
                AvisoLegalResponse.de(v.avisos()), ConferenciaMesResponse.de(v.conferencia(), podeConferir),
                v.excluidos().stream().map(AjusteResponse::de).toList());
    }
}
