package br.com.conferenciaponto.infrastructure.security;

import br.com.conferenciaponto.domain.model.Perfil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Segurança (prefixo {@code ponto.seguranca}).
 *
 * @param jwt              segredo HS256 (≥ 32 bytes), validade e emissor do token
 * @param usuariosIniciais criados na primeira subida se ainda não existirem (senha vazia = gerada e exibida no log)
 */
@ConfigurationProperties(prefix = "ponto.seguranca")
public record SegurancaProperties(
        @DefaultValue Jwt jwt,
        List<UsuarioInicial> usuariosIniciais) {

    public record Jwt(
            String segredo,
            @DefaultValue("8h") Duration expiracao,
            @DefaultValue("conferencia-ponto") String emissor) {
    }

    public record UsuarioInicial(String login, String nome, String senha, List<Perfil> perfis) {
    }

    public List<UsuarioInicial> usuariosIniciais() {
        return usuariosIniciais == null ? List.of() : usuariosIniciais;
    }
}
