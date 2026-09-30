package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Usuario;

import java.time.Instant;

/** Emite o token de acesso (JWT na infraestrutura). */
public interface EmissorToken {

    TokenEmitido emitir(Usuario usuario);

    record TokenEmitido(String valor, Instant expiraEm) {
    }
}
