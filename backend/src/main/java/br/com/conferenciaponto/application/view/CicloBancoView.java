package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.LocalDate;
import java.util.List;

/**
 * Ciclo do banco de horas com o saldo apurado.
 *
 * @param saldoSegundos     ciclo aberto: saldo atual (dias fechados desde o início); fechado: saldo final congelado
 * @param diasEmAberto      dias do ciclo com batida faltando/em andamento (fora do saldo)
 * @param diasAtePrevisao   dias até a previsão de término (negativo = já passou); só no ciclo aberto
 * @param sugestaoFechamento último dia sugerido para o botão "Fechar banco de horas"; só no ciclo aberto
 * @param meses             consolidação mês a mês dentro do ciclo (acumulado desde o início do ciclo)
 */
public record CicloBancoView(CicloBanco ciclo, int saldoSegundos, int diasRegistrados, int diasEmAberto,
                             Long diasAtePrevisao, LocalDate sugestaoFechamento,
                             List<SaldoMensal> meses) {
}
