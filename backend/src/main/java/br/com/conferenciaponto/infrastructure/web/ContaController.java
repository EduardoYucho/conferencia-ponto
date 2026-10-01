package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarUsuariosUseCase;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.importacao.VerificadorPasta;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.PastaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.PastaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.UsuarioResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Minha conta" (a senha fica em PUT /api/v1/auth/senha e o horário em /api/v1/horarios).
 * <pre>
 * PUT  /api/v1/conta/pasta            {pasta}  grava a pasta dos meus comprovantes (vazia = sem monitoramento)
 * POST /api/v1/conta/pasta/verificar  {pasta}  só confere se o servidor enxerga a pasta
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/conta")
public class ContaController {

    private final GerenciarUsuariosUseCase usuarios;
    private final AcessoUsuarios acesso;
    private final VerificadorPasta verificador;

    public ContaController(GerenciarUsuariosUseCase usuarios, AcessoUsuarios acesso, VerificadorPasta verificador) {
        this.usuarios = usuarios;
        this.acesso = acesso;
        this.verificador = verificador;
    }

    @PutMapping("/pasta")
    public ApiResponse<PastaResponse> pasta(@RequestBody PastaRequest r) {
        Usuario eu = acesso.alvoDeEdicao(acesso.logado(), null);
        Usuario alterado = usuarios.definirPasta(eu.id(), r.pasta(), eu);
        VerificadorPasta.Resultado v = verificador.verificar(alterado.pastaComprovantes());
        return ApiResponse.ok(new PastaResponse(alterado.pastaComprovantes(), v.acessivel(), v.aviso(),
                UsuarioResponse.de(alterado)));
    }

    @PostMapping("/pasta/verificar")
    public ApiResponse<PastaResponse> verificar(@RequestBody PastaRequest r) {
        Usuario eu = acesso.alvoDeEdicao(acesso.logado(), null);
        VerificadorPasta.Resultado v = verificador.verificar(r.pasta());
        return ApiResponse.ok(new PastaResponse(r.pasta() == null ? null : r.pasta().strip(), v.acessivel(),
                v.aviso(), UsuarioResponse.de(eu)));
    }
}
