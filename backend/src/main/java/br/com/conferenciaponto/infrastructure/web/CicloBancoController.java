package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.CicloBancoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.CorrigirCicloRequest;
import br.com.conferenciaponto.infrastructure.web.dto.FechamentoCicloResponse;
import br.com.conferenciaponto.infrastructure.web.dto.FecharCicloRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Ciclo (semestral) do banco de horas.
 * <pre>
 * GET  /api/v1/ciclos                      todos os ciclos (o aberto primeiro), com saldo
 * GET  /api/v1/ciclos/atual                ciclo aberto: saldo, previsão, dias restantes, meses
 * PUT  /api/v1/ciclos/atual                corrige {dataInicio, dataFimPrevista?} do ciclo aberto
 * POST /api/v1/ciclos/fechar               fecha {ultimoDia?, observacao?} e abre o próximo zerado
 *      (também em /api/ciclos/fechar)
 * POST /api/v1/ciclos/desfazer-fechamento  o ciclo anterior volta a ser o aberto
 * </pre>
 */
@RestController
public class CicloBancoController {

    private final GerenciarCicloBancoUseCase ciclos;

    public CicloBancoController(GerenciarCicloBancoUseCase ciclos) {
        this.ciclos = ciclos;
    }

    @GetMapping("/api/v1/ciclos")
    public ApiResponse<List<CicloBancoResponse>> listar() {
        return ApiResponse.ok(ciclos.listar().stream().map(CicloBancoResponse::de).toList());
    }

    @GetMapping("/api/v1/ciclos/atual")
    public ApiResponse<CicloBancoResponse> atual() {
        return ApiResponse.ok(CicloBancoResponse.de(ciclos.atual()));
    }

    @PutMapping("/api/v1/ciclos/atual")
    public ApiResponse<CicloBancoResponse> corrigir(@Valid @RequestBody CorrigirCicloRequest r) {
        return ApiResponse.ok(CicloBancoResponse.de(ciclos.corrigirPeriodo(r.dataInicio(), r.dataFimPrevista())));
    }

    @PostMapping({"/api/v1/ciclos/fechar", "/api/ciclos/fechar"})
    public ApiResponse<FechamentoCicloResponse> fechar(@Valid @RequestBody(required = false) FecharCicloRequest r,
                                                       Principal usuario) {
        FecharCicloRequest pedido = r != null ? r : new FecharCicloRequest(null, null);
        return ApiResponse.ok(FechamentoCicloResponse.de(
                ciclos.fechar(pedido.ultimoDia(), pedido.observacao(), usuario.getName())));
    }

    @PostMapping("/api/v1/ciclos/desfazer-fechamento")
    public ApiResponse<CicloBancoResponse> desfazer() {
        return ApiResponse.ok(CicloBancoResponse.de(ciclos.desfazerUltimoFechamento()));
    }
}
