package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.time.Instant;
import java.util.UUID;

/** Uma linha da lista de atendimentos. */
public record ResumoDoAtendimento(UUID id, String chamado, String contato, Instant inicio, Instant fim,
                                  SituacaoDoAtendimento situacao, Instant criadoEm, int mensagens, int anexos,
                                  Instant linksValidosAte) {
}
