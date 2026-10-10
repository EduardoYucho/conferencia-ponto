package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.time.Instant;
import java.util.UUID;

/**
 * O que o download precisa de um anexo da conversa.
 *
 * @param url link assinado (dá acesso ao arquivo): nunca vai para o log nem para o toString
 */
public record ArquivoParaBaixar(UUID id, UUID atendimentoId, int ordem, String nome, String url, Instant validoAte,
                                SituacaoDoArquivo situacao) {

    @Override
    public String toString() {
        return "ArquivoParaBaixar[id=" + id + ", atendimentoId=" + atendimentoId + ", ordem=" + ordem + ", situacao="
                + situacao + "]";
    }
}
