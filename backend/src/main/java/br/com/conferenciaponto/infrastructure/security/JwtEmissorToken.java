package br.com.conferenciaponto.infrastructure.security;

import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.EmissorToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Emite o JWT de acesso: sub = login e a claim "nome". As permissões não vão no token: são relidas do banco a
 * cada requisição (ver SecurityConfig), para uma mudança de acesso valer na hora.
 */
@Component
public class JwtEmissorToken implements EmissorToken {

    private final JwtEncoder encoder;
    private final SegurancaProperties properties;
    private final Clock clock;

    public JwtEmissorToken(JwtEncoder encoder, SegurancaProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public TokenEmitido emitir(Usuario usuario) {
        Instant agora = clock.instant();
        Instant expira = agora.plus(properties.jwt().expiracao());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.jwt().emissor())
                .issuedAt(agora)
                .expiresAt(expira)
                .subject(usuario.login())
                .claim(SecurityConfig.CLAIM_NOME, usuario.nome())
                .build();
        JwsHeader cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(cabecalho, claims)).getTokenValue();
        return new TokenEmitido(token, expira);
    }
}
