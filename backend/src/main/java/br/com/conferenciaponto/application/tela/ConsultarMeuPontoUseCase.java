package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.tela.DiaView.Filtro;
import br.com.conferenciaponto.application.tela.DiaView.Situacao;
import br.com.conferenciaponto.application.usecase.ConsultarJornadaUseCase;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** A tela "Meu ponto": os dias do mês já com situação, frase e ações, e os totais. */
@Service
@Transactional(readOnly = true)
public class ConsultarMeuPontoUseCase {

    private final MontadorDeDias montador;
    private final ConsultarJornadaUseCase consultar;
    private final RegrasJornada regras;
    private final Clock clock;

    public ConsultarMeuPontoUseCase(MontadorDeDias montador, ConsultarJornadaUseCase consultar, RegrasJornada regras,
                                    Clock clock) {
        this.montador = montador;
        this.consultar = consultar;
        this.regras = regras;
        this.clock = clock;
    }

    public MeuPontoView mes(UUID usuarioId, YearMonth referencia, MontadorDeDias.Quem quem) {
        LocalDate hoje = LocalDate.now(clock);
        List<DiaView> todos = montador.montar(usuarioId, referencia.atDay(1), referencia.atEndOfMonth(), quem);

        List<DiaView> ateHoje = new ArrayList<>(todos.stream().filter(d -> !d.futuro()).toList());
        Collections.reverse(ateHoje); // o mais recente em cima
        List<DiaView> proximos = todos.stream().filter(DiaView::futuro).toList();

        Map<Filtro, Integer> contagem = new EnumMap<>(Filtro.class);
        for (Filtro filtro : Filtro.values()) {
            contagem.put(filtro, (int) ateHoje.stream().filter(d -> d.filtros().contains(filtro)).count());
        }

        SaldoMensal resumo = consultar.saldos(usuarioId, referencia).meses().get(referencia.getMonthValue() - 1);
        MeuPontoView.Totais totais = new MeuPontoView.Totais(resumo.segundosTrabalhados(), resumo.segundosPrevistos(),
                resumo.saldoJornadasSegundos(), resumo.segundosLancados(), resumo.saldoMensalSegundos(),
                resumo.diasRegistrados() - resumo.diasEmAberto(),
                (int) ateHoje.stream().filter(d -> d.situacao() == Situacao.INCOMPLETO).count(),
                (int) ateHoje.stream().filter(d -> d.situacao() == Situacao.SEM_REGISTRO && !d.hoje()).count());

        LocalDate referenciaDoHorario = referencia.equals(YearMonth.from(hoje)) ? hoje
                : referencia.isBefore(YearMonth.from(hoje)) ? referencia.atEndOfMonth() : referencia.atDay(1);
        return new MeuPontoView(referencia, Horas.mesAno(referencia), referencia.equals(YearMonth.from(hoje)), totais,
                contagem, ateHoje, proximos, explicacao(regras.horario(usuarioId, referenciaDoHorario)));
    }

    /** "Seu horário é 08:00–12:00 e 13:00–17:48 (8h 48min por dia). Diferenças de até 5 minutos..." */
    static String explicacao(HorarioTrabalho horario) {
        Map<String, Long> porGrade = horario.dias().values().stream()
                .collect(Collectors.groupingBy(ConsultarMeuPontoUseCase::texto, LinkedHashMap::new, Collectors.counting()));
        String jornada;
        if (porGrade.size() == 1) {
            GradeHoraria grade = horario.dias().values().iterator().next();
            jornada = "O horário é %s (%s por dia), %s.".formatted(texto(grade),
                    Horas.duracao(grade.cargaHorariaSegundos()), diasDaSemana(horario));
        } else {
            jornada = "O horário muda conforme o dia da semana (veja em Minha conta).";
        }
        String tolerancia = horario.toleranciaMinutos() == 0
                ? "Não há tolerância: qualquer diferença em relação ao horário entra no saldo"
                : "Diferenças de até %d minuto%s em cada batida não contam; o que passar disso entra no saldo"
                        .formatted(horario.toleranciaMinutos(), horario.toleranciaMinutos() == 1 ? "" : "s");
        return jornada + " " + tolerancia + ": a favor quando houve trabalho a mais, devendo quando houve a menos. "
                + "É a mesma conta que o RH faz.";
    }

    /** "08:00–12:00 e 13:00–17:48" */
    static String texto(GradeHoraria grade) {
        return grade.periodos().stream()
                .map(p -> Horas.hora(p.entrada()) + "–" + Horas.hora(p.saida()))
                .collect(Collectors.joining(" e "));
    }

    private static String diasDaSemana(HorarioTrabalho horario) {
        List<DayOfWeek> dias = horario.dias().keySet().stream().sorted().toList();
        List<DayOfWeek> segundaASexta = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        if (dias.equals(segundaASexta)) {
            return "de segunda a sexta";
        }
        if (dias.size() == 7) {
            return "todos os dias";
        }
        String[] nomes = {"segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo"};
        return "em " + dias.stream().map(d -> nomes[d.getValue() - 1]).collect(Collectors.joining(", "));
    }
}
