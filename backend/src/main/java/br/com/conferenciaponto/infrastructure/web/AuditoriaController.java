package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConsultarAuditoriaUseCase;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.AuditoriaMesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** GET /api/v1/auditoria?ano=&mes= — grade da coordenação (somente leitura). */
@RestController
@RequestMapping("/api/v1/auditoria")
public class AuditoriaController {

    private final ConsultarAuditoriaUseCase consultar;
    private final Clock clock;

    public AuditoriaController(ConsultarAuditoriaUseCase consultar, Clock clock) {
        this.consultar = consultar;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<AuditoriaMesResponse> mes(@RequestParam(required = false) Integer ano,
                                                 @RequestParam(required = false) Integer mes, Titular titular) {
        return ApiResponse.ok(AuditoriaMesResponse.de(
                consultar.mes(titular.id(), ReferenciaMes.resolver(ano, mes, clock))));
    }
}
