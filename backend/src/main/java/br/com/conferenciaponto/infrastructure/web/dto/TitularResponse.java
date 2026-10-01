package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Usuario;

import java.util.UUID;

/** Usuário com dados de ponto (seletor "ver como" do administrador e da coordenação). */
public record TitularResponse(UUID id, String login, String nome) {

    public static TitularResponse de(Usuario u) {
        return new TitularResponse(u.id(), u.login(), u.nome());
    }
}
