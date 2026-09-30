package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.SessaoView;

import java.time.Instant;

public record SessaoResponse(String token, String tipo, Instant expiraEm, UsuarioResponse usuario) {

    public static SessaoResponse de(SessaoView s) {
        return new SessaoResponse(s.token(), "Bearer", s.expiraEm(), UsuarioResponse.de(s.usuario()));
    }
}
