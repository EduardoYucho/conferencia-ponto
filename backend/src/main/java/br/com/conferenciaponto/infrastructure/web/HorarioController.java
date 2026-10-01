package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarHorariosUseCase;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.AlteracaoHorarioResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.HorarioRequest;
import br.com.conferenciaponto.infrastructure.web.dto.HorarioResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Horário de trabalho com vigência. O próprio usuário ou o administrador ({@code ?usuario=login}) alteram.
 * <pre>
 * GET    /api/v1/horarios          vigências (da mais antiga para a mais recente)
 * POST   /api/v1/horarios          {vigenteDesde, toleranciaMinutos, dias: {SEG: "08:00-12:00 13:00-17:48", ...}}
 * DELETE /api/v1/horarios/{id}     remove uma vigência (os dias voltam ao horário anterior)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/horarios")
public class HorarioController {

    private final GerenciarHorariosUseCase horarios;
    private final AcessoUsuarios acesso;

    public HorarioController(GerenciarHorariosUseCase horarios, AcessoUsuarios acesso) {
        this.horarios = horarios;
        this.acesso = acesso;
    }

    @GetMapping
    public ApiResponse<List<HorarioResponse>> listar(Titular titular) {
        return ApiResponse.ok(horarios.listar(titular.id()).stream().map(HorarioResponse::de).toList());
    }

    @PostMapping
    public ApiResponse<AlteracaoHorarioResponse> salvar(@Valid @RequestBody HorarioRequest r,
                                                        @RequestParam(required = false) String usuario) {
        Usuario logado = acesso.logado();
        Usuario alvo = acesso.alvoDeEdicao(logado, usuario);
        return ApiResponse.ok(AlteracaoHorarioResponse.de(horarios.salvar(alvo.id(), r.vigenteDesde(),
                r.toleranciaMinutos(), r.diasDominio(), logado.login())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<AlteracaoHorarioResponse> excluir(@PathVariable UUID id,
                                                         @RequestParam(required = false) String usuario) {
        Usuario alvo = acesso.alvoDeEdicao(acesso.logado(), usuario);
        return ApiResponse.ok(AlteracaoHorarioResponse.de(horarios.excluir(alvo.id(), id)));
    }
}
