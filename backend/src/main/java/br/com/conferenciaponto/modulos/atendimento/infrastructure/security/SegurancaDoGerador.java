package br.com.conferenciaponto.modulos.atendimento.infrastructure.security;

import br.com.conferenciaponto.infrastructure.log.FiltroUsuarioNoLog;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
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
 * Segurança das rotas do gerador, separada da do ponto (que decide pelo método HTTP: lá quem só consulta não
 * grava nada). Aqui vale o mesmo login (JWT) e as mesmas permissões (relidas a cada requisição); quem decide se a
 * pessoa usa o gerador é a permissão atendimentos.usar ({@code InterceptorDoGerador}). As respostas 401/403 são
 * as mesmas do ponto (com protocolo).
 *
 * <pre>
 * Rota                                       Exige
 * GET  /api/v1/atendimentos/meu-acesso       sessão
 * GET  /api/v1/atendimentos/espaco           atendimentos.espaco
 * /api/v1/atendimentos/** (o resto)          atendimentos.usar (no InterceptorDoGerador)
 * </pre>
 */
@Configuration
public class SegurancaDoGerador {

    @Bean
    @Order(1)
    SecurityFilterChain cadeiaDoGerador(HttpSecurity http, AuthenticationEntryPoint semSessao,
                                        AccessDeniedHandler semPermissao, JwtAuthenticationConverter conversor)
            throws Exception {
        http
                .securityMatcher("/api/v1/atendimentos", "/api/v1/atendimentos/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(regras -> regras
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/atendimentos/espaco").hasAuthority(PermissoesDoAtendimento.ESPACO)
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
