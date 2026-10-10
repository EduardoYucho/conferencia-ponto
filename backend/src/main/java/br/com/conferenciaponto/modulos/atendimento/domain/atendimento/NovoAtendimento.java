package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ConversaLida;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Omitidos;

import java.time.Instant;
import java.util.UUID;

/**
 * Um atendimento criado a partir do PDF, pronto para ser gravado.
 *
 * @param conversa já mascarada (sem a chave do bot nem dados de acesso remoto)
 */
public record NovoAtendimento(UUID id, UUID usuarioId, ConversaLida conversa, Omitidos omitidos, int versaoLeitor,
                              Instant criadoEm, Instant apagarArquivosEm, Instant apagarTextosEm) {

    @Override
    public String toString() {
        return "NovoAtendimento[id=" + id + ", usuarioId=" + usuarioId + "]";
    }
}
