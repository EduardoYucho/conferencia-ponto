package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.NotificacoesUseCase;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.NotificacoesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * <pre>
 * GET  /api/v1/notificacoes            últimas 30 + quantidade não lida
 * POST /api/v1/notificacoes/{id}/lida  marca uma como lida
 * POST /api/v1/notificacoes/lidas      marca todas como lidas
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/notificacoes")
public class NotificacaoController {

    private final NotificacoesUseCase notificacoes;

    public NotificacaoController(NotificacoesUseCase notificacoes) {
        this.notificacoes = notificacoes;
    }

    @GetMapping
    public ApiResponse<NotificacoesResponse> listar(Titular titular) {
        return ApiResponse.ok(NotificacoesResponse.de(notificacoes.listar(titular.id())));
    }

    @PostMapping("/{id}/lida")
    public ApiResponse<NotificacoesResponse> marcarLida(@PathVariable UUID id, Titular titular) {
        notificacoes.marcarLida(titular.id(), id);
        return ApiResponse.ok(NotificacoesResponse.de(notificacoes.listar(titular.id())));
    }

    @PostMapping("/lidas")
    public ApiResponse<NotificacoesResponse> marcarTodas(Titular titular) {
        notificacoes.marcarTodasLidas(titular.id());
        return ApiResponse.ok(NotificacoesResponse.de(notificacoes.listar(titular.id())));
    }
}
