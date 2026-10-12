package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;

import java.util.UUID;

/**
 * Uma saída no acompanhamento ao vivo: a versão mais recente de cada tipo.
 *
 * @param situacao pendente, redigindo, pronta, falhou ou confirmada
 * @param erro     por que falhou (ou a última tentativa que não deu certo), para a pessoa ler
 */
public record EstadoDaSaida(UUID id, String tipo, String rotulo, int versao, String situacao, String erro,
                            String assunto) {

    public static EstadoDaSaida de(SaidaGuardada s) {
        return new EstadoDaSaida(s.id(), s.tipo().codigo(), s.tipo().rotulo(), s.versao(), s.situacao().codigo(),
                s.erroMensagem(), s.assunto());
    }
}
