package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A chave da API do Gemini de um usuário (cada um usa a própria; ninguém usa a de outro). Guardada cifrada; da
 * chave em si, só os últimos caracteres aparecem na tela.
 *
 * @param nivelPagoConfirmado o usuário confirmou que a chave é de um projeto com faturamento ativo (no nível
 *                            gratuito o Google pode usar o conteúdo enviado para melhorar os produtos dele)
 */
public record ChaveGemini(UUID usuarioId, ChaveCifrada cifrada, String ultimosCaracteres, boolean nivelPagoConfirmado,
                          SituacaoDaChave situacao, Instant testadaEm, Instant atualizadaEm) {

    public ChaveGemini {
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(cifrada, "cifrada");
        Objects.requireNonNull(ultimosCaracteres, "ultimosCaracteres");
        Objects.requireNonNull(situacao, "situacao");
    }

    public ChaveGemini comSituacao(SituacaoDaChave nova, Instant testada) {
        return new ChaveGemini(usuarioId, cifrada, ultimosCaracteres, nivelPagoConfirmado, nova, testada, atualizadaEm);
    }
}
