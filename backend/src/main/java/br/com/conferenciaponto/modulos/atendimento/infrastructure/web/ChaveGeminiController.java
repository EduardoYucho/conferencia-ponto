package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.chave.ChaveGeminiView;
import br.com.conferenciaponto.modulos.atendimento.application.chave.GerenciarChaveGemini;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A chave da API do Gemini da pessoa logada (só a própria; quem não tem o gerador liberado recebe 403).
 *
 * <pre>
 * GET    /api/v1/atendimentos/chave-gemini          {cadastrada, ultimosCaracteres, nivelPagoConfirmado, situacao, ...}
 * PUT    /api/v1/atendimentos/chave-gemini          {chave, nivelPagoConfirmado}: testa com o Google e guarda cifrada
 * POST   /api/v1/atendimentos/chave-gemini/testar   testa de novo a chave guardada
 * DELETE /api/v1/atendimentos/chave-gemini          apaga
 * </pre>
 *
 * A chave nunca volta nas respostas: só os 4 últimos caracteres.
 */
@RestController
@RequestMapping("/api/v1/atendimentos/chave-gemini")
public class ChaveGeminiController {

    /** O toString esconde a chave (para ela não aparecer em nenhum log ou mensagem). */
    public record CadastrarChaveRequest(
            @NotBlank(message = "Cole a chave da API do Gemini.")
            @Size(max = 300, message = "Isso é grande demais para uma chave da API do Gemini.")
            String chave,
            boolean nivelPagoConfirmado) {

        @Override
        public String toString() {
            return "CadastrarChaveRequest[chave=(oculta), nivelPagoConfirmado=" + nivelPagoConfirmado + "]";
        }
    }

    private final GerenciarChaveGemini chaves;
    private final AcessoUsuarios acesso;

    public ChaveGeminiController(GerenciarChaveGemini chaves, AcessoUsuarios acesso) {
        this.chaves = chaves;
        this.acesso = acesso;
    }

    @GetMapping
    public ApiResponse<ChaveGeminiView> estado() {
        return ApiResponse.ok(chaves.estado(acesso.logado()));
    }

    @PutMapping
    public ApiResponse<ChaveGeminiView> cadastrar(@Valid @RequestBody CadastrarChaveRequest pedido) {
        return ApiResponse.ok(chaves.cadastrar(acesso.logado(), pedido.chave(), pedido.nivelPagoConfirmado()));
    }

    @PostMapping("/testar")
    public ApiResponse<ChaveGeminiView> testar() {
        return ApiResponse.ok(chaves.testar(acesso.logado()));
    }

    @DeleteMapping
    public ApiResponse<ChaveGeminiView> apagar() {
        return ApiResponse.ok(chaves.apagar(acesso.logado()));
    }
}
