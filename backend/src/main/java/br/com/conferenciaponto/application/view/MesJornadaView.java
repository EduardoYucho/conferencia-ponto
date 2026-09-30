package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.YearMonth;
import java.util.List;

/**
 * Dias registrados no mês + consolidação do mês (com saldo anual acumulado até ele) + ausências
 * (férias, atestados...) que tocam o mês, para o painel rotular os dias sem registro.
 */
public record MesJornadaView(YearMonth referencia, List<RegistroJornadaView> dias, SaldoMensal resumo,
                             List<Ausencia> ausencias, List<Feriado> feriados) {
}
