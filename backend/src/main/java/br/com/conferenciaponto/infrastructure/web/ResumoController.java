package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.usecase.ConsultarJornadaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ConfiguracaoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.SaldosResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * <pre>
 * GET /api/v1/saldos?ano=2026&mes=9   saldo mensal, anual acumulado, série dos 12 meses e ciclo aberto do banco
 * GET /api/v1/configuracao            horário do usuário (grade, tolerância, jornada base) e relógio do servidor
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class ResumoController {

    private final ConsultarJornadaUseCase consultar;
    private final GerenciarCicloBancoUseCase ciclos;
    private final RegrasJornada regras;
    private final Clock clock;

    public ResumoController(ConsultarJornadaUseCase consultar, GerenciarCicloBancoUseCase ciclos,
                            RegrasJornada regras, Clock clock) {
        this.consultar = consultar;
        this.ciclos = ciclos;
        this.regras = regras;
        this.clock = clock;
    }

    @GetMapping("/saldos")
    public ApiResponse<SaldosResponse> saldos(@RequestParam(required = false) Integer ano,
                                              @RequestParam(required = false) Integer mes, Titular titular) {
        CicloBancoView ciclo;
        try {
            ciclo = ciclos.atual(titular.id());
        } catch (RecursoNaoEncontradoException semCiclo) {
            ciclo = null;
        }
        return ApiResponse.ok(SaldosResponse.de(
                consultar.saldos(titular.id(), ReferenciaMes.resolver(ano, mes, clock)), ciclo));
    }

    /** Horário do titular que vale hoje (a grade de hoje ou, num dia sem expediente, a do primeiro dia útil). */
    @GetMapping("/configuracao")
    public ApiResponse<ConfiguracaoResponse> configuracao(Titular titular) {
        LocalDateTime agora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        HorarioTrabalho horario = regras.horario(titular.id(), agora.toLocalDate());
        MotorCalculoJornadaService motor = horario.motor(agora.toLocalDate());
        return ApiResponse.ok(ConfiguracaoResponse.de(horario, motor, clock.getZone().getId(), agora.toLocalDate(),
                agora.toLocalTime()));
    }
}
