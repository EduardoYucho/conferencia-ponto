package br.com.expedio.modulos.conhecimento.infrastructure.web;

import br.com.expedio.domain.model.Usuario;
import br.com.expedio.infrastructure.web.acesso.AcessoUsuarios;
import br.com.expedio.infrastructure.web.dto.ApiResponse;
import br.com.expedio.modulos.conhecimento.application.acesso.PermissoesDaBase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O que a pessoa pode na base de conhecimento (o menu decide por isto).
 *
 * <pre>
 * GET /api/v1/conhecimento/meu-acesso          qualquer pessoa: {pesquisar, curar}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/conhecimento")
public class AcessoABaseController {

    public record MeuAcessoResponse(boolean pesquisar, boolean curar) {
    }

    private final AcessoUsuarios acesso;

    public AcessoABaseController(AcessoUsuarios acesso) {
        this.acesso = acesso;
    }

    @GetMapping("/meu-acesso")
    public ApiResponse<MeuAcessoResponse> meuAcesso() {
        Usuario logado = acesso.logado();
        boolean ativo = logado.ativo();
        return ApiResponse.ok(new MeuAcessoResponse(ativo && logado.pode(PermissoesDaBase.PESQUISAR),
                ativo && logado.pode(PermissoesDaBase.CURAR)));
    }
}
