package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.application.espaco.EspacoDosAtendimentos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O que a pessoa pode no gerador (o menu decide por isto) e o espaço em disco dos atendimentos.
 *
 * <pre>
 * GET /api/v1/atendimentos/meu-acesso   qualquer pessoa: {gerador, espaco, iaLocal}
 * GET /api/v1/atendimentos/espaco       atendimentos.espaco: espaço ocupado pelos atendimentos e livre no disco
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos")
public class AcessoAoGeradorController {

    /**
     * @param gerador usa os atendimentos
     * @param espaco  vê o espaço em disco dos atendimentos
     * @param iaLocal os textos são feitos pela IA local (sem chave do Gemini: a tela da chave sai do menu)
     */
    public record MeuAcessoResponse(boolean gerador, boolean espaco, boolean iaLocal) {
    }

    private final EspacoDosAtendimentos espaco;
    private final AcessoUsuarios acesso;
    private final boolean iaLocal;

    public AcessoAoGeradorController(EspacoDosAtendimentos espaco, AcessoUsuarios acesso,
                                     @Value("${atendimento.ia.motor:local}") String motor) {
        this.espaco = espaco;
        this.acesso = acesso;
        this.iaLocal = ConfiguracaoDaAnalise.local(motor);
    }

    @GetMapping("/espaco")
    public ApiResponse<EspacoDosAtendimentos.EspacoView> espaco() {
        return ApiResponse.ok(espaco.consultar(acesso.logado()));
    }

    @GetMapping("/meu-acesso")
    public ApiResponse<MeuAcessoResponse> meuAcesso() {
        Usuario logado = acesso.logado();
        return ApiResponse.ok(new MeuAcessoResponse(logado.ativo() && logado.pode(PermissoesDoAtendimento.USAR),
                logado.pode(PermissoesDoAtendimento.ESPACO), iaLocal));
    }
}
