package br.com.conferenciaponto.domain.acesso;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do histórico de acessos: quem mudou o quê, e quando.
 *
 * @param usuarioId a pessoa cujo acesso mudou (null = mudança num perfil)
 * @param perfilId  o perfil criado, editado ou excluído (null = mudança numa pessoa)
 */
public record MudancaDeAcesso(Instant quando, String quem, UUID usuarioId, UUID perfilId, String descricao) {
}
