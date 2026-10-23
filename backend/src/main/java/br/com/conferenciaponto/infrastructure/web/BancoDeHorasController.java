package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConferirMesUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarBancoDeHorasUseCase;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.BancoDeHorasMesResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ConferenciaResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.YearMonth;

/**
 * A tela Banco de horas.
 * <pre>
 * GET  /api/v1/banco-de-horas?ano=&mes=                     carga x horário, compensação, dias, avisos e conferência
 * POST /api/v1/banco-de-horas/conferencia?ano=&mes=&usuario=   {observacao}  marca o mês como conferido (ponto.conferir)
 * POST /api/v1/banco-de-horas/conferencia/reabrir?ano=&mes=&usuario=   {motivo}  reabre o mês (ponto.conferir)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/banco-de-horas")
public class BancoDeHorasController {

    private final ConsultarBancoDeHorasUseCase consultar;
    private final ConferirMesUseCase conferir;
    private final AcessoUsuarios acesso;
    private final Clock clock;

    public BancoDeHorasController(ConsultarBancoDeHorasUseCase consultar, ConferirMesUseCase conferir,
                                  AcessoUsuarios acesso, Clock clock) {
        this.consultar = consultar;
        this.conferir = conferir;
        this.acesso = acesso;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<BancoDeHorasMesResponse> mes(@RequestParam(required = false) Integer ano,
                                                    @RequestParam(required = false) Integer mes, Titular titular) {
        YearMonth referencia = ReferenciaMes.resolver(ano, mes, clock);
        return ApiResponse.ok(BancoDeHorasMesResponse.de(consultar.mes(titular.id(), referencia),
                titular.logado().pode(PermissoesDoPonto.CONFERIR)));
    }

    public record ConferirRequest(@Size(max = 300, message = "A observação pode ter no máximo 300 caracteres.")
                                  String observacao) {
    }

    public record ReabrirRequest(@Size(max = 300, message = "O motivo pode ter no máximo 300 caracteres.")
                                 String motivo) {
    }

    @PostMapping("/conferencia")
    public ApiResponse<ConferenciaResponse> conferir(@RequestParam int ano, @RequestParam int mes,
                                                     @RequestParam(required = false) String usuario,
                                                     @Valid @RequestBody(required = false) ConferirRequest corpo) {
        Usuario logado = acesso.logado();
        Usuario alvo = acesso.alvoDaConferencia(logado, usuario);
        return ApiResponse.ok(ConferenciaResponse.de(conferir.conferir(alvo.id(), ReferenciaMes.resolver(ano, mes, clock),
                corpo == null ? null : corpo.observacao(), logado.login())));
    }

    @PostMapping("/conferencia/reabrir")
    public ApiResponse<ConferenciaResponse> reabrir(@RequestParam int ano, @RequestParam int mes,
                                                    @RequestParam(required = false) String usuario,
                                                    @Valid @RequestBody(required = false) ReabrirRequest corpo) {
        Usuario logado = acesso.logado();
        Usuario alvo = acesso.alvoDaConferencia(logado, usuario);
        return ApiResponse.ok(ConferenciaResponse.de(conferir.reabrir(alvo.id(), ReferenciaMes.resolver(ano, mes, clock),
                corpo == null ? null : corpo.motivo(), logado.login())));
    }
}
