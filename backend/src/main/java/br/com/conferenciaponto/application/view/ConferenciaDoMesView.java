package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.util.List;

/**
 * Como está a conferência do mês.
 *
 * @param vigente   a conferência que vale (null = não conferido)
 * @param mudou     os números de agora são diferentes dos da conferência (um PDF chegou, um feriado foi cadastrado)
 * @param agora     os números de agora
 * @param historico todas as conferências do mês, da mais recente para a mais antiga (inclusive as reabertas)
 * @param terminou  o mês já terminou (só então dá para conferir)
 */
public record ConferenciaDoMesView(ConferenciaMes vigente, boolean mudou, SaldoMensal agora,
                                   List<ConferenciaMes> historico, boolean terminou) {
}
