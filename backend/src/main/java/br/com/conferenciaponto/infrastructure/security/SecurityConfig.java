package br.com.conferenciaponto.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

/**
 * Autenticação stateless com JWT (HS256) e autorização por perfil (RBAC).
 *
 * <pre>
 * Rota                                   ADMIN  USER  VIEWER  anônimo
 * POST /api/v1/auth/login                  ✓     ✓      ✓       ✓
 * PUT  /api/v1/auth/senha                  ✓     ✓      ✓       –
 * GET  /api/**  (leitura, SSE, download)   ✓     ✓      ✓       –
 * POST/PUT/PATCH/DELETE /api/**            ✓     ✓      –       –
 * GET  fora de /api (front-end: HTML/JS)   ✓     ✓      ✓       ✓
 * qualquer outra rota                      negada
 * </pre>
 *
 * <p>O front-end é público porque só tem código (os dados vêm da API, que exige o token).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String CLAIM_PERFIS = "roles";
    public static final String CLAIM_NOME = "nome";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String[] ESCRITA = {"ADMIN", "USER"};
    private static final String[] LEITURA = {"ADMIN", "USER", "VIEWER"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RespostasSeguranca respostas,
                                                   JwtAuthenticationConverter conversor) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // API stateless com Bearer token: sem cookie de sessão
                .cors(Customizer.withDefaults())       // usa o mapeamento de WebConfig
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(regras -> regras
                        // despachos internos (conclusão do SSE, página de erro) já passaram pela autorização
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/auth/senha").authenticated()
                        // leitura: todos os perfis
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole(LEITURA)
                        // escrita: registros manuais, batidas, exclusões e processamento de arquivos
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole(ESCRITA)
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole(ESCRITA)
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole(ESCRITA)
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole(ESCRITA)
                        // front-end empacotado no jar (index.html, assets e rotas do Vue): só leitura
                        .requestMatchers(SecurityConfig::leituraDoFrontend).permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversor))
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas));
        return http.build();
    }

    /** GET/HEAD fora de /api: arquivos do front-end e rotas do Vue (ver FrontendConfig). */
    static boolean leituraDoFrontend(HttpServletRequest requisicao) {
        String metodo = requisicao.getMethod();
        if (!HttpMethod.GET.matches(metodo) && !HttpMethod.HEAD.matches(metodo)) {
            return false;
        }
        String caminho = requisicao.getRequestURI().substring(requisicao.getContextPath().length());
        return !caminho.equals("/api") && !caminho.startsWith("/api/");
    }

    /** As authorities vêm da claim "roles" já com o prefixo ROLE_ (ex.: ROLE_VIEWER). */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter perfis = new JwtGrantedAuthoritiesConverter();
        perfis.setAuthoritiesClaimName(CLAIM_PERFIS);
        perfis.setAuthorityPrefix("");
        JwtAuthenticationConverter conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(perfis);
        return conversor;
    }

    @Bean
    public SecretKey chaveJwt(SegurancaProperties properties) {
        String segredo = properties.jwt().segredo();
        if (segredo == null || segredo.isBlank()) {
            log.warn("PONTO_JWT_SEGREDO não definido: usando chave aleatória (os tokens deixam de valer ao reiniciar).");
            try {
                KeyGenerator gerador = KeyGenerator.getInstance("HmacSHA256");
                gerador.init(256);
                return gerador.generateKey();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("ponto.seguranca.jwt.segredo precisa ter pelo menos 32 bytes (HS256).");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey chaveJwt) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chaveJwt));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey chaveJwt, SegurancaProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chaveJwt).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().emissor()));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
