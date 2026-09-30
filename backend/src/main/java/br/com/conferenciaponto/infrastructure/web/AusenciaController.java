package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarAusenciasUseCase;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.AusenciaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.AusenciaResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * GET    /api/v1/ausencias?inicio=&fim=   férias, atestados, licenças e folgas do período (padrão: ano atual)
 * POST   /api/v1/ausencias                cadastra {dataInicio, dataFim, tipo, descricao}
 * DELETE /api/v1/ausencias/{id}           remove (os dias voltam a ser úteis)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/ausencias")
public class AusenciaController {

    private final GerenciarAusenciasUseCase ausencias;
    private final Clock clock;

    public AusenciaController(GerenciarAusenciasUseCase ausencias, Clock clock) {
        this.ausencias = ausencias;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<AusenciaResponse>> listar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        int ano = LocalDate.now(clock).getYear();
        LocalDate de = inicio != null ? inicio : LocalDate.of(ano - 1, 1, 1);
        LocalDate ate = fim != null ? fim : LocalDate.of(ano + 1, 12, 31);
        return ApiResponse.ok(ausencias.listar(de, ate).stream().map(AusenciaResponse::de).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AusenciaResponse> cadastrar(@Valid @RequestBody AusenciaRequest r, Principal usuario) {
        return ApiResponse.ok(AusenciaResponse.de(
                ausencias.cadastrar(r.dataInicio(), r.dataFim(), r.tipo(), r.descricao(), usuario.getName())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> excluir(@PathVariable UUID id) {
        ausencias.excluir(id);
        return ApiResponse.ok(null);
    }
}
