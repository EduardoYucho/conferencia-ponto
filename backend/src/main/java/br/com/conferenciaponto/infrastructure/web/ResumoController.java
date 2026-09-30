package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarJornadaUseCase;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
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
 * GET /api/v1/configuracao            grade oficial, tolerância, jornada base e relógio do servidor
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class ResumoController {

    private final ConsultarJornadaUseCase consultar;
    private final GerenciarCicloBancoUseCase ciclos;
    private final MotorCalculoJornadaService motor;
    private final Clock clock;

    public ResumoController(ConsultarJornadaUseCase consultar, GerenciarCicloBancoUseCase ciclos,
                            MotorCalculoJornadaService motor, Clock clock) {
        this.consultar = consultar;
        this.ciclos = ciclos;
        this.motor = motor;
        this.clock = clock;
    }

    @GetMapping("/saldos")
    public ApiResponse<SaldosResponse> saldos(@RequestParam(required = false) Integer ano,
                                              @RequestParam(required = false) Integer mes) {
        CicloBancoView ciclo;
        try {
            ciclo = ciclos.atual();
        } catch (RecursoNaoEncontradoException semCiclo) {
            ciclo = null;
        }
        return ApiResponse.ok(SaldosResponse.de(consultar.saldos(ReferenciaMes.resolver(ano, mes, clock)), ciclo));
    }

    @GetMapping("/configuracao")
    public ApiResponse<ConfiguracaoResponse> configuracao() {
        GradeHoraria g = motor.grade();
        LocalDateTime agora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        return ApiResponse.ok(new ConfiguracaoResponse(
                new ConfiguracaoResponse.Grade(g.entrada1(), g.saida1(), g.entrada2(), g.saida2()),
                motor.jornadaBaseSegundos(),
                motor.toleranciaMinutos(),
                clock.getZone().getId(),
                agora.toLocalDate(),
                agora.toLocalTime()));
    }
}
