package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.AcessoDaPessoaView;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.MudancaView;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.PerfilView;
import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.Permissao;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Perfis de acesso e o acesso de cada pessoa (só com "Perfis e acessos", ver SecurityConfig).
 *
 * <pre>
 * GET    /api/v1/acessos/permissoes          as permissões que existem, em grupos
 * GET    /api/v1/acessos/perfis              os perfis, com o que liberam e quem os tem
 * POST   /api/v1/acessos/perfis              {nome, descricao?, permissoes}
 * PUT    /api/v1/acessos/perfis/{id}         {nome, descricao?, permissoes}
 * DELETE /api/v1/acessos/perfis/{id}         o perfil sai de quem o tinha
 * GET    /api/v1/acessos/usuarios/{id}       perfis, ajustes e o que vale para a pessoa (e por quê)
 * PUT    /api/v1/acessos/usuarios/{id}       {perfis: [id], ajustes: [{permissao, liberada}]}
 * GET    /api/v1/acessos/historico?usuario=&limite=   as últimas mudanças
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/acessos")
public class AcessosController {

    public record PerfilRequest(@NotBlank(message = "Dê um nome ao perfil.") String nome, String descricao,
                                List<String> permissoes) {
    }

    public record AjusteRequest(@NotBlank(message = "Informe a permissão do ajuste.") String permissao,
                                @NotNull(message = "Informe se a permissão fica liberada ou retirada.") Boolean liberada) {
    }

    public record AcessoRequest(List<UUID> perfis, List<@Valid AjusteRequest> ajustes) {
    }

    private final GerenciarAcessosUseCase acessos;
    private final AcessoUsuarios acesso;

    public AcessosController(GerenciarAcessosUseCase acessos, AcessoUsuarios acesso) {
        this.acessos = acessos;
        this.acesso = acesso;
    }

    @GetMapping("/permissoes")
    public ApiResponse<List<CatalogoDePermissoes.Grupo>> permissoes() {
        return ApiResponse.ok(acessos.catalogo());
    }

    @GetMapping("/perfis")
    public ApiResponse<List<PerfilView>> perfis() {
        return ApiResponse.ok(acessos.perfis());
    }

    @PostMapping("/perfis")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PerfilView> criar(@Valid @RequestBody PerfilRequest r) {
        return ApiResponse.ok(acessos.criarPerfil(acesso.logado(), r.nome(), r.descricao(), r.permissoes()));
    }

    @PutMapping("/perfis/{id}")
    public ApiResponse<PerfilView> alterar(@PathVariable UUID id, @Valid @RequestBody PerfilRequest r) {
        return ApiResponse.ok(acessos.alterarPerfil(acesso.logado(), id, r.nome(), r.descricao(), r.permissoes()));
    }

    @DeleteMapping("/perfis/{id}")
    public ApiResponse<Void> excluir(@PathVariable UUID id) {
        acessos.excluirPerfil(acesso.logado(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/usuarios/{id}")
    public ApiResponse<AcessoDaPessoaView> acessoDe(@PathVariable UUID id) {
        return ApiResponse.ok(acessos.acessoDe(id));
    }

    @PutMapping("/usuarios/{id}")
    public ApiResponse<AcessoDaPessoaView> definir(@PathVariable UUID id, @Valid @RequestBody AcessoRequest r) {
        List<AjusteDeAcesso> ajustes = r.ajustes() == null ? List.of() : r.ajustes().stream().map(a -> {
            if (!Permissao.codigoValido(a.permissao())) {
                throw new RegraNegocioException("PERMISSAO_DESCONHECIDA",
                        "A permissão \"%s\" não existe. Recarregue a tela.".formatted(a.permissao()));
            }
            return new AjusteDeAcesso(a.permissao(), a.liberada());
        }).toList();
        return ApiResponse.ok(acessos.definirAcesso(acesso.logado(), id, r.perfis(), ajustes));
    }

    @GetMapping("/historico")
    public ApiResponse<List<MudancaView>> historico(@RequestParam(required = false) UUID usuario,
                                                    @RequestParam(defaultValue = "50") int limite) {
        return ApiResponse.ok(acessos.historico(usuario, limite));
    }
}
