package br.com.expedio.application.view;

import br.com.expedio.application.PendenciasDoPeriodo.Pendencias;
import br.com.expedio.domain.model.ConferenciaMes;
import br.com.expedio.domain.model.SaldoMensal;

import java.util.List;

/**
 * Como está a conferência do mês.
 *
 * @param vigente   a conferência que vale (null = não conferido)
 * @param mudou     os números de agora são diferentes dos da conferência (um PDF chegou, um feriado foi cadastrado)
 * @param agora     os números de agora
 * @param historico todas as conferências do mês, da mais recente para a mais antiga (inclusive as reabertas)
 * @param terminou   o mês já terminou (só então dá para conferir)
 * @param pendencias o que está em aberto no mês, para avisar antes de conferir (vazio depois de conferido)
 */
public record ConferenciaDoMesView(ConferenciaMes vigente, boolean mudou, SaldoMensal agora,
                                   List<ConferenciaMes> historico, boolean terminou, Pendencias pendencias) {
}
