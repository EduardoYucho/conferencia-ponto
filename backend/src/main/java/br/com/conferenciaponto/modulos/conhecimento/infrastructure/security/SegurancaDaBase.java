package br.com.conferenciaponto.modulos.conhecimento.infrastructure.security;

import br.com.conferenciaponto.infrastructure.log.FiltroUsuarioNoLog;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Segurança das rotas da base de conhecimento, separada da do ponto: o mesmo login (JWT) e as mesmas permissões
 * (relidas a cada requisição); quem decide é a permissão base.pesquisar ({@code InterceptorDaBase}). As respostas
 * 401/403 são as mesmas do ponto (com protocolo).
 *
 * <pre>
 * Rota                                       Exige
 * GET  /api/v1/conhecimento/meu-acesso       sessão
 * /api/v1/conhecimento/** (o resto)          base.pesquisar (no InterceptorDaBase); a curadoria, base.curar
 * </pre>
 */
@Configuration
public class SegurancaDaBase {

    @Bean
    @Order(2)
    SecurityFilterChain cadeiaDaBase(HttpSecurity http, AuthenticationEntryPoint semSessao,
                                     AccessDeniedHandler semPermissao, JwtAuthenticationConverter conversor)
            throws Exception {
        http
                .securityMatcher("/api/v1/conhecimento", "/api/v1/conhecimento/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(regras -> regras
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversor))
                        .authenticationEntryPoint(semSessao)
                        .accessDeniedHandler(semPermissao))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(semSessao)
                        .accessDeniedHandler(semPermissao))
                .addFilterAfter(new FiltroUsuarioNoLog(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
