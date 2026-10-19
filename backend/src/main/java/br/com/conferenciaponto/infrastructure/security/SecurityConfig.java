package br.com.conferenciaponto.infrastructure.security;

import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.log.FiltroUsuarioNoLog;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
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
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

/**
 * Autenticação stateless com JWT (HS256) e autorização por permissão. O token leva só o login: a cada
 * requisição o usuário é relido do banco e as authorities são as permissões que valem para ele agora (a soma dos
 * perfis com os ajustes só dele). Assim, trocar um perfil, um ajuste ou desativar a pessoa vale na hora.
 *
 * <pre>
 * Rota                                          Exige
 * POST /api/v1/auth/login                       nada
 * /api/v1/auth/** (me, senha)                   sessão
 * /api/v1/acessos/** (perfis e acessos)         acessos.gerenciar
 * GET  /api/v1/usuarios/titulares               sessão (cada um vê quem pode consultar)
 * GET  /api/v1/usuarios                         usuarios.gerenciar ou acessos.gerenciar
 * PUT  /api/v1/usuarios/{id}/pasta              ponto.equipe.configurar
 * /api/v1/usuarios/** (cadastro)                usuarios.gerenciar
 * /api/v1/integracoes/** (conta Google)         integracoes.gerenciar
 * /api/v1/logs/**                               logs.ver
 * POST /api/v1/erros-de-tela                    sessão (a tela avisa o servidor de um erro dela)
 * POST/DELETE /api/v1/feriados/**               ponto.feriados
 * POST/PUT/DELETE horários e planilha           ponto.lancar ou ponto.equipe.configurar (?usuario= de outra pessoa)
 * POST /api/v1/notificacoes/**                  ponto.proprio
 * GET  /api/** (leitura, SSE, download)         sessão (de quem são os dados: AcessoUsuarios)
 * POST/PUT/PATCH/DELETE /api/** (o resto)       ponto.lancar
 * GET  fora de /api (front-end: HTML/JS)        nada
 * qualquer outra rota                           negada
 * </pre>
 *
 * <p>O front-end é público porque só tem código (os dados vêm da API, que exige o token). Quem vê os dados de
 * quem (cada um os seus; com ponto.equipe, os de todos) é decidido em AcessoUsuarios. Os módulos têm as cadeias
 * deles (com o mesmo conversor de token).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String CLAIM_NOME = "nome";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

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
                        .requestMatchers("/api/v1/auth", "/api/v1/auth/**").authenticated()
                        // administração
                        .requestMatchers("/api/v1/acessos", "/api/v1/acessos/**").hasAuthority(PermissoesDaAdministracao.ACESSOS)
                        .requestMatchers(HttpMethod.GET, "/api/v1/usuarios/titulares").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/usuarios")
                        .hasAnyAuthority(PermissoesDaAdministracao.USUARIOS, PermissoesDaAdministracao.ACESSOS)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/usuarios/*/pasta").hasAuthority(PermissoesDoPonto.CONFIGURAR_EQUIPE)
                        .requestMatchers("/api/v1/usuarios", "/api/v1/usuarios/**").hasAuthority(PermissoesDaAdministracao.USUARIOS)
                        .requestMatchers("/api/v1/integracoes", "/api/v1/integracoes/**")
                        .hasAuthority(PermissoesDaAdministracao.INTEGRACOES)
                        .requestMatchers("/api/v1/logs", "/api/v1/logs/**").hasAuthority(PermissoesDaAdministracao.LOGS)
                        .requestMatchers(HttpMethod.POST, "/api/v1/erros-de-tela").authenticated()
                        // feriados valem para todos
                        .requestMatchers(HttpMethod.POST, "/api/v1/feriados", "/api/v1/feriados/**").hasAuthority(PermissoesDoPonto.FERIADOS)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/feriados/**").hasAuthority(PermissoesDoPonto.FERIADOS)
                        // leitura: quem tem sessão (de quem são os dados é decidido em AcessoUsuarios)
                        // conferir e reabrir o mês de uma pessoa (antes da regra geral das gravações)
                        .requestMatchers("/api/v1/banco-de-horas/conferencia", "/api/v1/banco-de-horas/conferencia/**")
                        .hasAuthority(PermissoesDoPonto.CONFERIR)
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        // horário e planilha: os próprios ou, com ?usuario=, os de outra pessoa
                        .requestMatchers("/api/v1/horarios", "/api/v1/horarios/**", "/api/v1/planilha", "/api/v1/planilha/**")
                        .hasAnyAuthority(PermissoesDoPonto.LANCAR, PermissoesDoPonto.CONFIGURAR_EQUIPE)
                        .requestMatchers("/api/v1/notificacoes", "/api/v1/notificacoes/**").hasAuthority(PermissoesDoPonto.PROPRIO)
                        // o resto que grava: registros manuais, batidas, exclusões e processamento de arquivos
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAuthority(PermissoesDoPonto.LANCAR)
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAuthority(PermissoesDoPonto.LANCAR)
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAuthority(PermissoesDoPonto.LANCAR)
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAuthority(PermissoesDoPonto.LANCAR)
                        // front-end empacotado no jar (index.html, assets e rotas do Vue): só leitura
                        .requestMatchers(SecurityConfig::leituraDoFrontend).permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(conversor))
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))
                // com o token conferido, o login entra no contexto de log (arquivo do usuário)
                .addFilterAfter(new FiltroUsuarioNoLog(), BearerTokenAuthenticationFilter.class);
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

    /**
     * As authorities são as permissões que valem agora para o login do token, relidas do banco a cada requisição.
     * Usuário que não existe mais ou foi desativado: 401 (ACESSO_DESATIVADO), e a tela volta ao login.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(UsuarioRepository usuarios) {
        JwtAuthenticationConverter conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(jwt -> permissoesDe(usuarios, jwt.getSubject()));
        return conversor;
    }

    static Collection<GrantedAuthority> permissoesDe(UsuarioRepository usuarios, String login) {
        Usuario usuario = login == null ? null : usuarios.buscarPorLogin(login).filter(Usuario::ativo).orElse(null);
        if (usuario == null) {
            throw new DisabledException(RespostasSeguranca.ACESSO_DESATIVADO);
        }
        return usuario.acesso().permissoes().stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
                .toList();
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
