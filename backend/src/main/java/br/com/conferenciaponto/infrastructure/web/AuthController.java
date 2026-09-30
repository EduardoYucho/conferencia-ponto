package br.com.conferenciaponto.infrastructure.web;

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

    private final AutenticarUsuarioUseCase autenticar;

    public AuthController(AutenticarUsuarioUseCase autenticar) {
        this.autenticar = autenticar;
    }

    @PostMapping("/login")
    public ApiResponse<SessaoResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(SessaoResponse.de(autenticar.autenticar(request.login(), request.senha())));
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
