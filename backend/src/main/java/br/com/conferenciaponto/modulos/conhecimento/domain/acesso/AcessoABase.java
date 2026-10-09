package br.com.conferenciaponto.modulos.conhecimento.domain.acesso;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Quem pode usar a base de conhecimento, liberado pelo administrador pessoa por pessoa (independe do perfil do
 * ponto). {@code pesquisar}: ver a base. {@code curar}: editar qualquer registro e mudar a situação no
 * desenvolvimento; quem cura também pesquisa.
 *
 * @param concedidoPor login de quem mudou o acesso por último
 */
public record AcessoABase(UUID usuarioId, boolean pesquisar, boolean curar, String concedidoPor,
                          Instant atualizadoEm) {

    public AcessoABase {
        Objects.requireNonNull(usuarioId, "usuarioId");
        pesquisar = pesquisar || curar;
    }

    public static AcessoABase semAcesso(UUID usuarioId) {
        return new AcessoABase(usuarioId, false, false, null, null);
    }
}
