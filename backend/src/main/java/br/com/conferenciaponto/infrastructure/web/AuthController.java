package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.application.view.SessaoView;
import br.com.conferenciaponto.infrastructure.log.ContextoDeLog;
import br.com.conferenciaponto.application.usecase.AutenticarUsuarioUseCase;
import br.com.conferenciaponto.infrastructure.web.dto.AlterarSenhaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.LoginRequest;
import br.com.conferenciaponto.infrastructure.web.dto.SessaoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.UsuarioResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/v1/auth/login   {login, senha} -> {token, tipo: "Bearer", expiraEm, usuario}
 * GET  /api/v1/auth/me      usuário do token
 * PUT  /api/v1/auth/senha   {senhaAtual, novaSenha}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    /** O login vem de fora: sem quebras de linha (não forja linhas de log) e cortado. */
    private static String limpar(String login) {
        String limpo = login == null ? "" : login.replaceAll("[\r\n\t]", " ").strip();
        return limpo.length() > 60 ? limpo.substring(0, 60) + "…" : limpo;
    }

    private final AutenticarUsuarioUseCase autenticar;

    public AuthController(AutenticarUsuarioUseCase autenticar) {
        this.autenticar = autenticar;
    }

    @PostMapping("/login")
    public ApiResponse<SessaoResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            SessaoView sessao = autenticar.autenticar(request.login(), request.senha());
            ContextoDeLog.comUsuario(sessao.usuario().login(), () -> log.info("Entrou no sistema"));
            return ApiResponse.ok(SessaoResponse.de(sessao));
        } catch (CredenciaisInvalidasException e) {
            // sem a senha, claro: só o login tentado, para o administrador reconhecer tentativas repetidas
            log.warn("Tentativa de login recusada para \"{}\"", limpar(request.login()));
            throw e;
        }
    }

    @GetMapping("/me")
    public ApiResponse<UsuarioResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(UsuarioResponse.de(autenticar.usuarioAtual(jwt.getSubject())));
    }

    @PutMapping("/senha")
    public ApiResponse<Void> alterarSenha(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody AlterarSenhaRequest request) {
        autenticar.alterarSenha(jwt.getSubject(), request.senhaAtual(), request.novaSenha());
        return ApiResponse.ok(null);
    }
}
