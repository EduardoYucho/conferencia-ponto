package br.com.expedio.modulos.atendimento.domain.atendimento;

import java.time.Instant;
import java.util.UUID;

/**
 * O que o vigia dos atendimentos precisa saber de um atendimento parado (novo ou pausado) ou com os arquivos para sair
 * em breve.
 *
 * @param atualizadoEm       a última mudança (num pausado, quando a pausa começou)
 * @param arquivosApagadosEm quando a limpeza apagou os arquivos ({@code null} = ainda estão no disco)
 * @param textoPronto        tem pelo menos um texto pronto ou confirmado
 */
public record AtendimentoParaOVigia(UUID id, UUID usuarioId, String chamado, SituacaoDoAtendimento situacao,
                                    String motivoPausa, Instant criadoEm, Instant atualizadoEm,
                                    Instant apagarArquivosEm, Instant arquivosApagadosEm, boolean textoPronto) {
}
