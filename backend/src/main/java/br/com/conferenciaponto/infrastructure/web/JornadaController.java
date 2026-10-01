package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.AjustarBatidasUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarJornadaUseCase;
import br.com.conferenciaponto.application.usecase.ExcluirRegistroUseCase;
import br.com.conferenciaponto.application.usecase.LancarRegistroManualUseCase;
import br.com.conferenciaponto.application.usecase.RegistrarBatidaUseCase;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.AjusteBatidasRequest;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ContextoAjusteResponse;
import br.com.conferenciaponto.infrastructure.web.dto.MesJornadaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.RegistrarBatidaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.RegistroJornadaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.RegistroManualRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;

/**
 * <pre>
 * GET    /api/v1/jornadas?ano=2026&mes=9   dias do mês + resumo (saldo mensal e anual acumulado)
 * GET    /api/v1/jornadas/{data}           um dia
 * POST   /api/v1/jornadas/batidas          registra a próxima batida  {data?, horario?}
 * POST   /api/v1/jornadas/manual           lançamento manual (fim de semana/feriado)
 * DELETE /api/v1/jornadas/{data}           exclui o registro do dia
 * PUT    /api/v1/jornadas/{data}/batidas   ajuste manual (correção do RH)  {horarios[], justificativa}
 * GET    /api/v1/jornadas/{data}/ajustes   batidas com PDF (travadas) + histórico de ajustes do dia
 * </pre>
 * Nos GET, {@code ?usuario=login} consulta outro titular (administrador e coordenação).
 */
@RestController
@RequestMapping("/api/v1/jornadas")
public class JornadaController {

    private final ConsultarJornadaUseCase consultar;
    private final RegistrarBatidaUseCase registrarBatida;
    private final LancarRegistroManualUseCase lancarManual;
    private final ExcluirRegistroUseCase excluir;
    private final AjustarBatidasUseCase ajustar;
    private final Clock clock;

    public JornadaController(ConsultarJornadaUseCase consultar, RegistrarBatidaUseCase registrarBatida,
                             LancarRegistroManualUseCase lancarManual, ExcluirRegistroUseCase excluir,
                             AjustarBatidasUseCase ajustar, Clock clock) {
        this.consultar = consultar;
        this.registrarBatida = registrarBatida;
        this.lancarManual = lancarManual;
        this.excluir = excluir;
        this.ajustar = ajustar;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<MesJornadaResponse> mes(@RequestParam(required = false) Integer ano,
                                               @RequestParam(required = false) Integer mes, Titular titular) {
        return ApiResponse.ok(MesJornadaResponse.de(
                consultar.mes(titular.id(), ReferenciaMes.resolver(ano, mes, clock))));
    }

    @GetMapping("/{data}")
    public ApiResponse<RegistroJornadaResponse> dia(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data, Titular titular) {
        return ApiResponse.ok(RegistroJornadaResponse.de(consultar.dia(titular.id(), data)));
    }

    @PostMapping("/batidas")
    public ResponseEntity<ApiResponse<RegistroJornadaResponse>> registrarBatida(
            @RequestBody(required = false) RegistrarBatidaRequest request, Titular titular) {
        RegistroJornadaView view = request == null
                ? registrarBatida.executar(titular.id(), null, null)
                : registrarBatida.executar(titular.id(), request.data(), request.horario());
        return criado(view);
    }

    @PostMapping("/manual")
    public ResponseEntity<ApiResponse<RegistroJornadaResponse>> lancarManual(
            @Valid @RequestBody RegistroManualRequest request, Titular titular) {
        return criado(lancarManual.executar(titular.id(), request.data(), request.intervalosDominio()));
    }

    @DeleteMapping("/{data}")
    public ApiResponse<Void> excluir(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
                                     Titular titular) {
        excluir.executar(titular.id(), data);
        return ApiResponse.ok(null);
    }

    /** O usuário vem do token (claim {@code sub}), nunca do corpo da requisição. */
    @PutMapping("/{data}/batidas")
    public ApiResponse<RegistroJornadaResponse> ajustar(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @Valid @RequestBody AjusteBatidasRequest request, Titular titular) {
        return ApiResponse.ok(RegistroJornadaResponse.de(ajustar.executar(titular.id(), data, request.horarios(),
                request.justificativa(), titular.quem())));
    }

    @GetMapping("/{data}/ajustes")
    public ApiResponse<ContextoAjusteResponse> ajustes(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data, Titular titular) {
        return ApiResponse.ok(ContextoAjusteResponse.de(ajustar.contexto(titular.id(), data)));
    }

    private static ResponseEntity<ApiResponse<RegistroJornadaResponse>> criado(RegistroJornadaView view) {
        return ResponseEntity
                .created(URI.create("/api/v1/jornadas/" + view.data()))
                .body(ApiResponse.ok(RegistroJornadaResponse.de(view)));
    }
}
