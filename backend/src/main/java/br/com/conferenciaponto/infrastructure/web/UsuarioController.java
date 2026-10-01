package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarUsuariosUseCase;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.importacao.GerenciadorMonitoresPdf;
import br.com.conferenciaponto.infrastructure.importacao.VerificadorPasta;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.AtualizarUsuarioRequest;
import br.com.conferenciaponto.infrastructure.web.dto.MonitoramentoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.NovoUsuarioRequest;
import br.com.conferenciaponto.infrastructure.web.dto.PastaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.PastaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.SenhaProvisoriaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.TitularResponse;
import br.com.conferenciaponto.infrastructure.web.dto.UsuarioAdminResponse;
import br.com.conferenciaponto.infrastructure.web.dto.UsuarioResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * GET  /api/v1/usuarios/titulares      quem tem dados de ponto (ADMIN e coordenação: todos; USER: só ele)
 * GET  /api/v1/usuarios                todos os usuários                         (ADMIN)
 * POST /api/v1/usuarios                {login, nome, perfil, senhaProvisoria, pastaComprovantes?}  (ADMIN)
 * PUT  /api/v1/usuarios/{id}           {nome, perfil, ativo}                    (ADMIN)
 * PUT  /api/v1/usuarios/{id}/senha     {senhaProvisoria}                        (ADMIN)
 * PUT  /api/v1/usuarios/{id}/pasta     {pasta}                                  (ADMIN)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/usuarios")
public class UsuarioController {

    private final GerenciarUsuariosUseCase usuarios;
    private final AcessoUsuarios acesso;
    private final GerenciadorMonitoresPdf monitores;
    private final VerificadorPasta verificador;

    public UsuarioController(GerenciarUsuariosUseCase usuarios, AcessoUsuarios acesso,
                             GerenciadorMonitoresPdf monitores, VerificadorPasta verificador) {
        this.usuarios = usuarios;
        this.acesso = acesso;
        this.monitores = monitores;
        this.verificador = verificador;
    }

    @GetMapping("/titulares")
    public ApiResponse<List<TitularResponse>> titulares() {
        Usuario logado = acesso.logado();
        List<Usuario> lista = logado.podeVerTodos()
                ? usuarios.titulares()
                : (logado.isTitular() ? List.of(logado) : List.of());
        return ApiResponse.ok(lista.stream().map(TitularResponse::de).toList());
    }

    @GetMapping
    public ApiResponse<List<UsuarioAdminResponse>> listar() {
        return ApiResponse.ok(usuarios.listar().stream().map(this::paraAdmin).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UsuarioAdminResponse> criar(@Valid @RequestBody NovoUsuarioRequest r) {
        Usuario novo = usuarios.criar(r.login(), r.nome(), r.perfil(), r.senhaProvisoria(), r.pastaComprovantes(),
                acesso.logado().login());
        return ApiResponse.ok(paraAdmin(novo));
    }

    @PutMapping("/{id}")
    public ApiResponse<UsuarioAdminResponse> atualizar(@PathVariable UUID id,
                                                       @Valid @RequestBody AtualizarUsuarioRequest r) {
        Usuario logado = acesso.logado();
        return ApiResponse.ok(paraAdmin(usuarios.atualizar(id, r.nome(), r.perfil(), r.ativo(), logado.id(),
                logado.login())));
    }

    @PutMapping("/{id}/senha")
    public ApiResponse<UsuarioAdminResponse> redefinirSenha(@PathVariable UUID id,
                                                            @Valid @RequestBody SenhaProvisoriaRequest r) {
        return ApiResponse.ok(paraAdmin(usuarios.redefinirSenha(id, r.senhaProvisoria())));
    }

    @PutMapping("/{id}/pasta")
    public ApiResponse<PastaResponse> pasta(@PathVariable UUID id, @RequestBody PastaRequest r) {
        Usuario alterado = usuarios.definirPasta(id, r.pasta(), acesso.logado());
        VerificadorPasta.Resultado v = verificador.verificar(alterado.pastaComprovantes());
        return ApiResponse.ok(new PastaResponse(alterado.pastaComprovantes(), v.acessivel(), v.aviso(),
                UsuarioResponse.de(alterado)));
    }

    private UsuarioAdminResponse paraAdmin(Usuario u) {
        MonitoramentoResponse monitor = !u.isTitular() ? null : monitores.estado(u.id())
                .map(e -> MonitoramentoResponse.de(e, List.of()))
                .orElseGet(() -> MonitoramentoResponse.semMonitor(u.pastaComprovantes(), List.of()));
        return UsuarioAdminResponse.de(u, monitor);
    }
}
