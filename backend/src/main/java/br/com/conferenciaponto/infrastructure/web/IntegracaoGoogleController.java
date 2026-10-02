package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase;
import br.com.conferenciaponto.infrastructure.google.SincronizadorPlanilhas;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ChaveGoogleRequest;
import br.com.conferenciaponto.infrastructure.web.dto.IntegracaoGoogleResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conta de serviço do Google com que o sistema grava as planilhas (só o administrador).
 * <pre>
 * GET    /api/v1/integracoes/google    {configurada, email, projeto, planilhas}
 * PUT    /api/v1/integracoes/google    {chave: "conteúdo do arquivo .json"}  confere com o Google e grava
 * DELETE /api/v1/integracoes/google    apaga a chave (as planilhas param de ser atualizadas)
 * </pre>
 * A chave nunca é devolvida pela API.
 */
@RestController
@RequestMapping("/api/v1/integracoes/google")
public class IntegracaoGoogleController {

    private final GerenciarPlanilhaUseCase planilhas;
    private final SincronizadorPlanilhas sincronizador;

    public IntegracaoGoogleController(GerenciarPlanilhaUseCase planilhas, SincronizadorPlanilhas sincronizador) {
        this.planilhas = planilhas;
        this.sincronizador = sincronizador;
    }

    @GetMapping
    public ApiResponse<IntegracaoGoogleResponse> consultar() {
        return ApiResponse.ok(IntegracaoGoogleResponse.de(planilhas.integracao()));
    }

    @PutMapping
    public ApiResponse<IntegracaoGoogleResponse> configurar(@RequestBody ChaveGoogleRequest r) {
        IntegracaoGoogleResponse resposta = IntegracaoGoogleResponse.de(planilhas.configurar(r.chave()));
        sincronizador.agendarTodos(); // conta trocada: as planilhas já vinculadas voltam a ser gravadas
        return ApiResponse.ok(resposta);
    }

    @DeleteMapping
    public ApiResponse<IntegracaoGoogleResponse> remover() {
        return ApiResponse.ok(IntegracaoGoogleResponse.de(planilhas.desconfigurar()));
    }
}
