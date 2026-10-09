package br.com.conferenciaponto.modulos.atendimento.domain.acesso;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Quem pode usar o gerador de textos de atendimento. É liberado pelo administrador, pessoa por pessoa, e não
 * depende do perfil do ponto: a coordenação, somente leitura no ponto, pode usar o gerador se for liberada; o
 * administrador também precisa ser liberado (ele libera a si mesmo).
 *
 * @param concedidoPor login de quem liberou ou retirou o acesso por último
 */
public record AcessoAoGerador(UUID usuarioId, boolean gerador, String concedidoPor, Instant atualizadoEm) {

    public AcessoAoGerador {
        Objects.requireNonNull(usuarioId, "usuarioId");
    }

    public static AcessoAoGerador semAcesso(UUID usuarioId) {
        return new AcessoAoGerador(usuarioId, false, null, null);
    }
}
