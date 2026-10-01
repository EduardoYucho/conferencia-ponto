package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.YearMonth;
import java.util.List;

/**
 * Dias registrados no mês + consolidação do mês (com saldo anual acumulado até ele) + ausências
 * (férias, atestados...) e feriados que tocam o mês, para o painel rotular os dias sem registro, + os
 * lançamentos avulsos no banco de horas do mês.
 */
public record MesJornadaView(YearMonth referencia, List<RegistroJornadaView> dias, SaldoMensal resumo,
                             List<Ausencia> ausencias, List<Feriado> feriados, List<LancamentoBanco> lancamentos) {

    public MesJornadaView(YearMonth referencia, List<RegistroJornadaView> dias, SaldoMensal resumo,
                          List<Ausencia> ausencias, List<Feriado> feriados) {
        this(referencia, dias, resumo, ausencias, feriados, List.of());
    }
}
