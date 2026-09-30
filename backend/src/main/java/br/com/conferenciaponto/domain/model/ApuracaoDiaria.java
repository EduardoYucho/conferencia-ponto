package br.com.conferenciaponto.domain.model;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Resultado imutável do motor de cálculo para um dia. Durações em SEGUNDOS, como no relatório do RH.
 *
 * @param marcacoes               sempre 6 itens, na ordem de {@link TipoBatida}
 * @param segundosTrabalhados     tempo bruto entre as batidas (intervalos fechados), como a coluna
 *                                "Hr. Trabalhadas" do RH
 * @param saldoDiarioSegundos     saldo com a tolerância aplicada (coluna "Hr. Extra/Falta" do RH);
 *                                {@code null} enquanto {@link StatusJornada#EM_ANDAMENTO}
 */
public record ApuracaoDiaria(
        TipoDia tipoDia,
        List<MarcacaoApurada> marcacoes,
        int jornadaPrevistaSegundos,
        int segundosTrabalhados,
        Integer saldoDiarioSegundos,
        StatusJornada status) {

    public ApuracaoDiaria {
        marcacoes = List.copyOf(marcacoes);
    }

    public MarcacaoApurada marcacao(TipoBatida tipo) {
        return marcacoes.get(tipo.ordinal());
    }

    public LocalTime considerado(TipoBatida tipo) {
        return marcacao(tipo).considerado();
    }

    public Optional<Integer> saldo() {
        return Optional.ofNullable(saldoDiarioSegundos);
    }

    public boolean isFechada() {
        return status == StatusJornada.FECHADA;
    }
}
