package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConsultarPresencaUseCase;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.PresencaResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * GET /api/v1/presenca   quem está trabalhando agora: todos os usuários ativos, com a situação e o motivo
 * </pre>
 * Qualquer usuário logado consulta. O detalhe de cada linha depende de quem pergunta (ver
 * {@link ConsultarPresencaUseCase}).
 */
@RestController
@RequestMapping("/api/v1/presenca")
public class PresencaController {

    private final ConsultarPresencaUseCase presenca;
    private final AcessoUsuarios acesso;

    public PresencaController(ConsultarPresencaUseCase presenca, AcessoUsuarios acesso) {
        this.presenca = presenca;
        this.acesso = acesso;
    }

    @GetMapping
    public ApiResponse<PresencaResponse> agora() {
        return ApiResponse.ok(PresencaResponse.de(presenca.agora(acesso.logado())));
    }
}
