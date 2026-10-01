package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
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
    public ApiResponse<List<CicloBancoResponse>> listar(Titular titular) {
        return ApiResponse.ok(ciclos.listar(titular.id()).stream().map(CicloBancoResponse::de).toList());
    }

    @GetMapping("/api/v1/ciclos/atual")
    public ApiResponse<CicloBancoResponse> atual(Titular titular) {
        return ApiResponse.ok(CicloBancoResponse.de(ciclos.atual(titular.id())));
    }

    @PutMapping("/api/v1/ciclos/atual")
    public ApiResponse<CicloBancoResponse> corrigir(@Valid @RequestBody CorrigirCicloRequest r, Titular titular) {
        return ApiResponse.ok(CicloBancoResponse.de(
                ciclos.corrigirPeriodo(titular.id(), r.dataInicio(), r.dataFimPrevista())));
    }

    @PostMapping({"/api/v1/ciclos/fechar", "/api/ciclos/fechar"})
    public ApiResponse<FechamentoCicloResponse> fechar(@Valid @RequestBody(required = false) FecharCicloRequest r,
                                                       Titular titular) {
        FecharCicloRequest pedido = r != null ? r : new FecharCicloRequest(null, null);
        return ApiResponse.ok(FechamentoCicloResponse.de(
                ciclos.fechar(titular.id(), pedido.ultimoDia(), pedido.observacao(), titular.quem())));
    }

    @PostMapping("/api/v1/ciclos/desfazer-fechamento")
    public ApiResponse<CicloBancoResponse> desfazer(Titular titular) {
        return ApiResponse.ok(CicloBancoResponse.de(ciclos.desfazerUltimoFechamento(titular.id())));
    }
}
