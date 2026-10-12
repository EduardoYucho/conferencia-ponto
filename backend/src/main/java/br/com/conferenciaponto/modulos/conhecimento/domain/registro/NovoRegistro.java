package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import java.time.Instant;
import java.util.UUID;

/** Um registro a criar a partir de um texto confirmado (o histórico começa com a situação inicial). */
public record NovoRegistro(UUID id, UUID atendimentoId, UUID saidaId, UUID autorId, String autorLogin,
                           DadosDoRegistro dados, String resolucaoHtml, SituacaoDoRegistro situacao, String chamadoDev,
                           Instant criadoEm) {
}
