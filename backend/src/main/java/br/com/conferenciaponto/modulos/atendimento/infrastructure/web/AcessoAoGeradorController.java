package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.AcessoAoGeradorView;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
import br.com.conferenciaponto.modulos.atendimento.application.espaco.EspacoDosAtendimentos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Acesso ao gerador.
 *
 * <pre>
 * GET /api/v1/atendimentos/meu-acesso          qualquer perfil: {gerador, administrador, iaLocal} (o menu decide por isto)
 * GET /api/v1/atendimentos/acessos             administrador: todos os usuários ativos com o acesso de cada um
 * PUT /api/v1/atendimentos/acessos/{usuarioId} administrador: {gerador: true|false}
 * GET /api/v1/atendimentos/acessos/espaco      administrador: espaço ocupado pelos atendimentos e livre no disco
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos")
public class AcessoAoGeradorController {

    /** @param iaLocal os textos são feitos pela IA local (sem chave do Gemini: a tela da chave sai do menu) */
    public record MeuAcessoResponse(boolean gerador, boolean administrador, boolean iaLocal) {
    }

    public record DefinirAcessoRequest(@NotNull(message = "Informe se o gerador fica liberado.") Boolean gerador) {
    }

    private final GerenciarAcessosAoGerador acessos;
    private final EspacoDosAtendimentos espaco;
    private final AcessoUsuarios acesso;
    private final boolean iaLocal;

    public AcessoAoGeradorController(GerenciarAcessosAoGerador acessos, EspacoDosAtendimentos espaco, AcessoUsuarios acesso,
                                     @Value("${atendimento.ia.motor:local}") String motor) {
        this.acessos = acessos;
        this.espaco = espaco;
        this.acesso = acesso;
        this.iaLocal = ConfiguracaoDaAnalise.local(motor);
    }

    @GetMapping("/acessos/espaco")
    public ApiResponse<EspacoDosAtendimentos.EspacoView> espaco() {
        return ApiResponse.ok(espaco.consultar(acesso.logado()));
    }

    @GetMapping("/meu-acesso")
    public ApiResponse<MeuAcessoResponse> meuAcesso() {
        Usuario logado = acesso.logado();
        return ApiResponse.ok(new MeuAcessoResponse(acessos.liberado(logado), logado.isAdmin(), iaLocal));
    }

    @GetMapping("/acessos")
    public ApiResponse<List<AcessoAoGeradorView>> listar() {
        return ApiResponse.ok(acessos.listar(acesso.logado()));
    }

    @PutMapping("/acessos/{usuarioId}")
    public ApiResponse<AcessoAoGeradorView> definir(@PathVariable UUID usuarioId,
                                                    @Valid @RequestBody DefinirAcessoRequest pedido) {
        return ApiResponse.ok(acessos.definir(acesso.logado(), usuarioId, pedido.gerador()));
    }
}
