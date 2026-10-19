package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.evento.ConferenciaAlteradaEvento;
import br.com.conferenciaponto.application.view.ConferenciaDoMesView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.port.ConferenciaMesRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Conferência do mês: quem confere (permissão ponto.conferir) marca o mês de uma pessoa como conferido, com os
 * números da hora, e ele fica travado para mudanças à mão ({@link TravaDosMesesConferidos}). Reabrir exige o motivo;
 * tudo fica no histórico do mês. Só um mês que já terminou pode ser conferido.
 */
@Service
public class ConferirMesUseCase {

    static final int MOTIVO_MINIMO = 5;

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ConferenciaMesRepository conferencias;
    private final ConsultarJornadaUseCase jornada;
    private final NotificacoesUseCase notificacoes;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public ConferirMesUseCase(ConferenciaMesRepository conferencias, ConsultarJornadaUseCase jornada,
                              NotificacoesUseCase notificacoes, ApplicationEventPublisher eventos, Clock clock) {
        this.conferencias = conferencias;
        this.jornada = jornada;
        this.notificacoes = notificacoes;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConferenciaDoMesView situacao(UUID usuarioId, YearMonth mes) {
        SaldoMensal agora = jornada.resumo(usuarioId, mes);
        List<ConferenciaMes> historico = conferencias.historico(usuarioId, mes);
        ConferenciaMes vigente = historico.stream().filter(ConferenciaMes::vale).findFirst().orElse(null);
        return new ConferenciaDoMesView(vigente, vigente != null && vigente.mudouDesde(agora), agora, historico, terminou(mes));
    }

    /** A conferência que vale para o mês (a planilha mostra quem conferiu). */
    @Transactional(readOnly = true)
    public java.util.Optional<ConferenciaMes> vigente(UUID usuarioId, YearMonth mes) {
        return conferencias.vigente(usuarioId, mes);
    }

    @Transactional
    public ConferenciaMes conferir(UUID usuarioId, YearMonth mes, String observacao, String quem) {
        if (!terminou(mes)) {
            throw new RegraNegocioException("MES_NAO_TERMINOU",
                    "%s ainda não terminou: dá para conferir a partir de %s.".formatted(
                            TravaDosMesesConferidos.nomeDoMes(mes), DATA.format(mes.plusMonths(1).atDay(1))));
        }
        conferencias.vigente(usuarioId, mes).ifPresent(c -> {
            throw new ConflitoException("MES_JA_CONFERIDO", "%s já foi conferido por %s."
                    .formatted(TravaDosMesesConferidos.nomeDoMes(mes), c.conferidoPor()));
        });
        SaldoMensal numeros = jornada.resumo(usuarioId, mes);
        ConferenciaMes nova = ConferenciaMes.nova(usuarioId, mes, quem, observacao, numeros, clock.instant());
        conferencias.salvar(nova);
        String texto = "%s conferido por %s".formatted(TravaDosMesesConferidos.nomeDoMes(mes), quem);
        notificacoes.notificar(usuarioId, TipoNotificacao.MES_CONFERIDO, "MES_CONFERIDO:%s".formatted(nova.id()), texto,
                "As horas, as batidas e os lançamentos do mês foram conferidos e ficam travados para mudanças à mão.",
                "/banco-de-horas?ano=%d&mes=%d".formatted(mes.getYear(), mes.getMonthValue()));
        eventos.publishEvent(new ConferenciaAlteradaEvento(usuarioId, mes, texto));
        return nova;
    }

    @Transactional
    public ConferenciaMes reabrir(UUID usuarioId, YearMonth mes, String motivo, String quem) {
        String texto = motivo == null ? "" : motivo.strip();
        if (texto.length() < MOTIVO_MINIMO) {
            throw new RegraNegocioException("REABERTURA_SEM_MOTIVO",
                    "Informe por que o mês vai ser reaberto (ex.: \"Faltou o atestado do dia 14\").");
        }
        ConferenciaMes vigente = conferencias.vigente(usuarioId, mes)
                .orElseThrow(() -> new RecursoNaoEncontradoException("MES_NAO_CONFERIDO",
                        "%s não está conferido.".formatted(TravaDosMesesConferidos.nomeDoMes(mes))));
        ConferenciaMes reaberta = vigente.reaberta(quem, texto, clock.instant());
        conferencias.salvar(reaberta);
        String titulo = "%s reaberto por %s".formatted(TravaDosMesesConferidos.nomeDoMes(mes), quem);
        notificacoes.notificar(usuarioId, TipoNotificacao.MES_CONFERIDO, "MES_REABERTO:%s".formatted(vigente.id()),
                titulo, "Motivo: %s. O mês voltou a aceitar mudanças.".formatted(texto),
                "/banco-de-horas?ano=%d&mes=%d".formatted(mes.getYear(), mes.getMonthValue()));
        eventos.publishEvent(new ConferenciaAlteradaEvento(usuarioId, mes, titulo));
        return reaberta;
    }

    private boolean terminou(YearMonth mes) {
        return mes.isBefore(YearMonth.from(LocalDate.now(clock)));
    }
}
