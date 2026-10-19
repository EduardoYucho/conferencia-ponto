package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.DiferencaDaCarga;
import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView.Bloco;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView.Compensacao;
import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.service.LeiDaJornada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Os avisos do sino sobre a carga semanal e as folgas:
 * <ul>
 *   <li>faltam horas para fechar a carga do mês e restam 2 fins de semana ou menos (um aviso por faixa);</li>
 *   <li>o mês terminou faltando horas (ficaram como débito no banco), nos primeiros dias do mês seguinte;</li>
 *   <li>7 dias seguidos sem folga, ou (a partir de 16/12/2026) semana sem nenhuma folga.</li>
 * </ul>
 * Cada aviso sai uma vez (a chave tem o mês, a faixa ou o dia). Roda todo dia, na subida e quando um dia sem
 * expediente é lançado.
 */
@Service
public class VerificarCompensacaoUseCase {

    /** Até quantos fins de semana restantes o aviso de compensação aparece. */
    static final int FINS_DE_SEMANA_PARA_AVISAR = 2;
    /** O aviso do mês que terminou faltando sai até este dia do mês seguinte. */
    static final int DIA_LIMITE_DO_AVISO_DO_MES_ANTERIOR = 10;
    /** Os avisos de folga olham para os últimos dias. */
    static final int DIAS_DAS_FOLGAS = 14;

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");
    private static final Set<String> FOLGAS = Set.of("SEM_FOLGA_7_DIAS", "SEMANA_SEM_FOLGA");
    private static final String LINK = "/banco-de-horas";

    private final ConsultarBancoDeHorasUseCase banco;
    private final DiferencaDaCarga carga;
    private final NotificacoesUseCase notificacoes;

    public VerificarCompensacaoUseCase(ConsultarBancoDeHorasUseCase banco, DiferencaDaCarga carga,
                                       NotificacoesUseCase notificacoes) {
        this.banco = banco;
        this.carga = carga;
        this.notificacoes = notificacoes;
    }

    @Transactional
    public List<Notificacao> executar(UUID usuarioId, LocalDate hoje) {
        List<Notificacao> criadas = new ArrayList<>();
        if (carga.temDiferenca(usuarioId)) {
            compensacaoDoMes(usuarioId, hoje).ifPresent(criadas::add);
            mesAnterior(usuarioId, hoje).ifPresent(criadas::add);
        }
        criadas.addAll(folgas(usuarioId, hoje));
        return criadas;
    }

    /** Depois de um lançamento (o evento chega com a transação dele já encerrada). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Notificacao> folgasAgora(UUID usuarioId, LocalDate hoje) {
        return folgas(usuarioId, hoje);
    }

    private Optional<Notificacao> compensacaoDoMes(UUID usuarioId, LocalDate hoje) {
        YearMonth mes = YearMonth.from(hoje);
        Compensacao c = banco.compensacao(usuarioId, mes);
        List<Bloco> restantes = c.finsDeSemanaRestantes();
        if (c.falta() <= 0 || restantes.isEmpty() || restantes.size() > FINS_DE_SEMANA_PARA_AVISAR) {
            return Optional.empty();
        }
        String quais = String.join(" e ", restantes.stream().map(b -> b.inicio().equals(b.fim())
                ? DIA.format(b.inicio()) : DIA.format(b.inicio()) + " a " + DIA.format(b.fim())).toList());
        String titulo = "Faltam %s para fechar a carga de %s".formatted(LeiDaJornada.duracao(c.falta()),
                nomeCurto(mes).toLowerCase(java.util.Locale.ROOT));
        String mensagem = (restantes.size() == 1
                ? "Resta 1 fim de semana no mês (%s).".formatted(quais)
                : "Restam %d fins de semana no mês (%s).".formatted(restantes.size(), quais))
                + " As horas do fim de semana compensam a diferença entre a carga semanal e o horário;"
                + " o que faltar no fim do mês fica como débito no banco de horas.";
        return notificacoes.notificar(usuarioId, TipoNotificacao.COMPENSACAO,
                "COMPENSACAO:%s:%d".formatted(mes, restantes.size()), titulo, mensagem, LINK);
    }

    private Optional<Notificacao> mesAnterior(UUID usuarioId, LocalDate hoje) {
        if (hoje.getDayOfMonth() > DIA_LIMITE_DO_AVISO_DO_MES_ANTERIOR) {
            return Optional.empty();
        }
        YearMonth mes = YearMonth.from(hoje).minusMonths(1);
        Compensacao c = banco.compensacao(usuarioId, mes);
        if (c.falta() <= 0) {
            return Optional.empty();
        }
        return notificacoes.notificar(usuarioId, TipoNotificacao.COMPENSACAO, "COMPENSACAO_FIM:%s".formatted(mes),
                "%s terminou faltando %s da carga".formatted(nomeCurto(mes), LeiDaJornada.duracao(c.falta())),
                "As horas que faltaram ficaram como débito no banco de horas.",
                "%s?ano=%d&mes=%d".formatted(LINK, mes.getYear(), mes.getMonthValue()));
    }

    /** "Outubro". */
    private static String nomeCurto(YearMonth mes) {
        String nome = TravaDosMesesConferidos.nomeDoMes(mes);
        return nome.substring(0, nome.indexOf(' '));
    }

    private List<Notificacao> folgas(UUID usuarioId, LocalDate hoje) {
        List<Notificacao> criadas = new ArrayList<>();
        for (AvisoLegal aviso : banco.avisos(usuarioId, hoje.minusDays(DIAS_DAS_FOLGAS), hoje, hoje)) {
            if (!FOLGAS.contains(aviso.codigo()) || aviso.data() == null) {
                continue;
            }
            notificacoes.notificar(usuarioId, TipoNotificacao.SEM_FOLGA,
                            "SEM_FOLGA:%s:%s".formatted(aviso.codigo(), aviso.data()), "Sem folga na semana",
                            aviso.mensagem() + " (" + aviso.fundamento() + ")",
                            "%s?ano=%d&mes=%d".formatted(LINK, aviso.data().getYear(), aviso.data().getMonthValue()))
                    .ifPresent(criadas::add);
        }
        return criadas;
    }
}
