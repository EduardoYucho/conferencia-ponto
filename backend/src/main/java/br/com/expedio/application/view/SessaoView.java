package br.com.expedio.application.view;

import br.com.expedio.domain.model.Usuario;

import java.time.Instant;

public record SessaoView(String token, Instant expiraEm, Usuario usuario) {
}
