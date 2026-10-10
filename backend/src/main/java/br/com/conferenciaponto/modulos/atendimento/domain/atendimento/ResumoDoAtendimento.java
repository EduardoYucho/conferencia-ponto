package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha da lista de atendimentos.
 *
 * @param anexos          anexos da conversa
 * @param linksValidosAte o link que vence primeiro entre os anexos que ainda faltam (null se nenhum falta)
 */
public record ResumoDoAtendimento(UUID id, String chamado, String contato, Instant inicio, Instant fim,
                                  SituacaoDoAtendimento situacao, Instant criadoEm, int mensagens, int anexos,
                                  Instant linksValidosAte) {
}
