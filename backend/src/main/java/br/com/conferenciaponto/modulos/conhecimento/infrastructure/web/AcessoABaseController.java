package br.com.conferenciaponto.modulos.conhecimento.infrastructure.web;

import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.AcessoABaseView;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.GerenciarAcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Acesso à base de conhecimento.
 *
 * <pre>
 * GET /api/v1/conhecimento/meu-acesso          qualquer perfil: {pesquisar, curar, administrador}
 * GET /api/v1/conhecimento/acessos             administrador: todos os usuários ativos com o acesso de cada um
 * PUT /api/v1/conhecimento/acessos/{usuarioId} administrador: {pesquisar, curar} (curar inclui pesquisar)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/conhecimento")
public class AcessoABaseController {

    public record MeuAcessoResponse(boolean pesquisar, boolean curar, boolean administrador) {
    }

    public record DefinirAcessoRequest(
            @NotNull(message = "Informe se a pessoa pode pesquisar na base.") Boolean pesquisar,
            @NotNull(message = "Informe se a pessoa pode curar a base.") Boolean curar) {
    }

    private final GerenciarAcessosABase acessos;
    private final AcessoUsuarios acesso;

    public AcessoABaseController(GerenciarAcessosABase acessos, AcessoUsuarios acesso) {
        this.acessos = acessos;
        this.acesso = acesso;
    }

    @GetMapping("/meu-acesso")
    public ApiResponse<MeuAcessoResponse> meuAcesso() {
        Usuario logado = acesso.logado();
        AcessoABase meu = acessos.acessoDe(logado);
        return ApiResponse.ok(new MeuAcessoResponse(meu.pesquisar(), meu.curar(), logado.isAdmin()));
    }

    @GetMapping("/acessos")
    public ApiResponse<List<AcessoABaseView>> listar() {
        return ApiResponse.ok(acessos.listar(acesso.logado()));
    }

    @PutMapping("/acessos/{usuarioId}")
    public ApiResponse<AcessoABaseView> definir(@PathVariable UUID usuarioId,
                                                @Valid @RequestBody DefinirAcessoRequest pedido) {
        return ApiResponse.ok(acessos.definir(acesso.logado(), usuarioId, pedido.pesquisar(), pedido.curar()));
    }
}
