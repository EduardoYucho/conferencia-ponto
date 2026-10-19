package br.com.conferenciaponto.application;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.port.ConferenciaMesRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * O mês conferido fica travado para mudanças à mão: batidas (ajuste, lançamento, exclusão, a batida de um dia
 * passado), lançamentos no banco, folgas e férias, e o horário que valeria nele. Os PDFs do relógio continuam
 * entrando (são a prova da marcação): a tela mostra que os números mudaram depois da conferência.
 */
@Component
public class TravaDosMesesConferidos {

    private static final String[] MESES = {"janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
            "agosto", "setembro", "outubro", "novembro", "dezembro"};
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Sem travas (testes que não tratam da conferência). */
    public static final TravaDosMesesConferidos NENHUMA = new TravaDosMesesConferidos(null, Clock.systemDefaultZone());


    private final ConferenciaMesRepository conferencias;
    private final DateTimeFormatter data;

    public TravaDosMesesConferidos(ConferenciaMesRepository conferencias, Clock clock) {
        this.conferencias = conferencias;
        this.data = DIA.withZone(clock.getZone());
    }

    /** O dia está num mês conferido: não muda. */
    public void exigirAberto(UUID usuarioId, LocalDate data) {
        if (conferencias == null || data == null) {
            return;
        }
        conferencias.vigente(usuarioId, YearMonth.from(data)).ifPresent(c -> {
            throw travado(c);
        });
    }

    /** Algum mês do período está conferido (ex.: férias de 20/10 a 10/11). */
    public void exigirAbertoNoPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        if (conferencias == null || inicio == null || fim == null) {
            return;
        }
        YearMonth ultimo = YearMonth.from(fim);
        conferencias.vigentesDesde(usuarioId, YearMonth.from(inicio)).stream()
                .filter(c -> !c.mes().isAfter(ultimo))
                .findFirst()
                .ifPresent(c -> {
                    throw travado(c);
                });
    }

    /** Uma mudança que vale da data em diante (horário): nenhum mês conferido pode estar no caminho. */
    public void exigirAbertoDesde(UUID usuarioId, LocalDate desde) {
        if (conferencias == null || desde == null) {
            return;
        }
        conferencias.vigentesDesde(usuarioId, YearMonth.from(desde)).stream().reduce((a, b) -> b).ifPresent(c -> {
            throw new ConflitoException("MES_CONFERIDO", ("%s foi conferido por %s em %s: o horário pode mudar a "
                    + "partir de %s. Para mudar antes, quem confere precisa reabrir o mês na tela Banco de horas.")
                    .formatted(nomeDoMes(c.mes()), c.conferidoPor(), data.format(c.conferidoEm()),
                            DIA.format(c.mes().plusMonths(1).atDay(1))));
        });
    }

    private ConflitoException travado(ConferenciaMes c) {
        return new ConflitoException("MES_CONFERIDO", ("%s foi conferido por %s em %s e está travado. Para mudar, "
                + "quem confere precisa reabrir o mês na tela Banco de horas.")
                .formatted(nomeDoMes(c.mes()), c.conferidoPor(), data.format(c.conferidoEm())));
    }

    /** "Outubro de 2026". */
    public static String nomeDoMes(YearMonth mes) {
        String nome = MESES[mes.getMonthValue() - 1];
        return Character.toUpperCase(nome.charAt(0)) + nome.substring(1) + " de " + mes.getYear();
    }
}
