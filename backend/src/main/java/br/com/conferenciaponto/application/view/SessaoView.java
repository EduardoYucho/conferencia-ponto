package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.Usuario;

import java.time.Instant;

public record SessaoView(String token, Instant expiraEm, Usuario usuario) {
}
