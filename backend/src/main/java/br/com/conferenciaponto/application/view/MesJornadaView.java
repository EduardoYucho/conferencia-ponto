package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Dias registrados no mês + consolidação do mês (com saldo anual acumulado até ele) + ausências
 * (férias, atestados...) e feriados que tocam o mês, para o painel rotular os dias sem registro, + os
 * lançamentos avulsos no banco de horas do mês + o expediente previsto de cada dia pelo horário do usuário.
 */
public record MesJornadaView(YearMonth referencia, List<RegistroJornadaView> dias, SaldoMensal resumo,
                             List<Ausencia> ausencias, List<Feriado> feriados, List<LancamentoBanco> lancamentos,
                             List<Expediente> expedientes) {

    /**
     * Dia com expediente no horário do usuário (dias sem expediente, como sábado e domingo no horário padrão,
     * não aparecem). Feriados e ausências não são descontados aqui.
     */
    public record Expediente(LocalDate data, int previstoSegundos, List<GradeHoraria.Periodo> periodos) {
    }

    public MesJornadaView(YearMonth referencia, List<RegistroJornadaView> dias, SaldoMensal resumo,
                          List<Ausencia> ausencias, List<Feriado> feriados) {
        this(referencia, dias, resumo, ausencias, feriados, List.of(), List.of());
    }
}
