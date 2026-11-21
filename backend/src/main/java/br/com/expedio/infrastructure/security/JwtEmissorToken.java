package br.com.expedio.infrastructure.security;

import br.com.expedio.domain.model.Usuario;
import br.com.expedio.domain.port.EmissorToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Emite o JWT de acesso: sub = login, a claim "nome" e a claim "sv" ({@link #impressaoDaSenha}). As permissões não
 * vão no token: são relidas do banco a cada requisição (ver SecurityConfig), para uma mudança de acesso valer na hora.
 * Pelo mesmo caminho, uma senha trocada (pela pessoa ou pelo administrador) encerra as sessões abertas com a antiga.
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
                .claim(SecurityConfig.CLAIM_SENHA, impressaoDaSenha(usuario.senhaHash()))
                .build();
        JwsHeader cabecalho = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(cabecalho, claims)).getTokenValue();
        return new TokenEmitido(token, expira);
    }

    /**
     * Um resumo curto do hash da senha guardado no banco (16 caracteres do SHA-256 dele): muda quando a senha muda e
     * não diz nada sobre ela.
     */
    static String impressaoDaSenha(String senhaHash) {
        try {
            byte[] resumo = MessageDigest.getInstance("SHA-256").digest(senhaHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(resumo).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
