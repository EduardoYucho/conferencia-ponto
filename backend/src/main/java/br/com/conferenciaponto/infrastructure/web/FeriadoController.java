package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarFeriadosUseCase;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.FeriadoRequest;
import br.com.conferenciaponto.infrastructure.web.dto.FeriadoResponse;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * <pre>
 * GET    /api/v1/feriados?inicio=&fim=   feriados do período (padrão: ano atual e o seguinte)
 * POST   /api/v1/feriados                {data, descricao, abrangencia: NACIONAL|ESTADUAL|MUNICIPAL|EMPRESA}
 * DELETE /api/v1/feriados/{data}         remove (o dia volta a ser útil)
 * </pre>
 * Feriados valem para todos os usuários: só o administrador cadastra e remove (ver SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/feriados")
public class FeriadoController {

    private final GerenciarFeriadosUseCase feriados;
    private final Clock clock;

    public FeriadoController(GerenciarFeriadosUseCase feriados, Clock clock) {
        this.feriados = feriados;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<FeriadoResponse>> listar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim) {
        int ano = LocalDate.now(clock).getYear();
        LocalDate de = inicio != null ? inicio : LocalDate.of(ano, 1, 1);
        LocalDate ate = fim != null ? fim : LocalDate.of(ano + 1, 12, 31);
        return ApiResponse.ok(feriados.listar(de, ate).stream().map(FeriadoResponse::de).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FeriadoResponse> cadastrar(@Valid @RequestBody FeriadoRequest r) {
        return ApiResponse.ok(FeriadoResponse.de(feriados.cadastrar(r.data(), r.descricao(), r.abrangencia())));
    }

    @DeleteMapping("/{data}")
    public ApiResponse<Void> excluir(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        feriados.excluir(data);
        return ApiResponse.ok(null);
    }
}
