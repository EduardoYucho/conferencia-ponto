package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConsultarAuditoriaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.AuditoriaMesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.YearMonth;

/**
 * GET /api/v1/auditoria?ano=&mes= — grade da coordenação (somente leitura), com o banco de horas no fim do mês pelo
 * ciclo (o mesmo do painel no mês corrente).
 */
@RestController
@RequestMapping("/api/v1/auditoria")
public class AuditoriaController {

    private final ConsultarAuditoriaUseCase consultar;
    private final GerenciarCicloBancoUseCase ciclos;
    private final Clock clock;

    public AuditoriaController(ConsultarAuditoriaUseCase consultar, GerenciarCicloBancoUseCase ciclos, Clock clock) {
        this.consultar = consultar;
        this.ciclos = ciclos;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<AuditoriaMesResponse> mes(@RequestParam(required = false) Integer ano,
                                                 @RequestParam(required = false) Integer mes, Titular titular) {
        YearMonth referencia = ReferenciaMes.resolver(ano, mes, clock);
        return ApiResponse.ok(AuditoriaMesResponse.de(consultar.mes(titular.id(), referencia),
                ciclos.bancoNoFimDoMes(titular.id(), referencia)));
    }
}
