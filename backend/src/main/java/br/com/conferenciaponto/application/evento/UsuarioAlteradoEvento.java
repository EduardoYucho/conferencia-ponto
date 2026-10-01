package br.com.conferenciaponto.application.evento;

import java.util.UUID;

/**
 * Cadastro de um usuário mudou (criado, perfil, ativo, pasta de comprovantes): os monitores de pasta se
 * reorganizam e as telas abertas atualizam a lista de usuários.
 */
public record UsuarioAlteradoEvento(UUID usuarioId, String descricao) {
}
