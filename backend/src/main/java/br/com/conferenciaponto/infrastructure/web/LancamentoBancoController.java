package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarLancamentosBancoUseCase;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.LancamentoBancoRequest;
import br.com.conferenciaponto.infrastructure.web.dto.LancamentoBancoResponse;
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
import java.util.UUID;

/**
 * <pre>
 * GET    /api/v1/lancamentos-banco?inicio=&fim=   lançamentos avulsos no banco de horas (padrão: ano atual)
 * POST   /api/v1/lancamentos-banco                {data, duracao: "04:00", sentido: DEBITO|CREDITO, descricao}
 * DELETE /api/v1/lancamentos-banco/{id}           remove o lançamento
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/lancamentos-banco")
public class LancamentoBancoController {

    private final GerenciarLancamentosBancoUseCase lancamentos;
    private final Clock clock;

    public LancamentoBancoController(GerenciarLancamentosBancoUseCase lancamentos, Clock clock) {
        this.lancamentos = lancamentos;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<LancamentoBancoResponse>> listar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
            Titular titular) {
        int ano = LocalDate.now(clock).getYear();
        LocalDate de = inicio != null ? inicio : LocalDate.of(ano, 1, 1);
        LocalDate ate = fim != null ? fim : LocalDate.of(ano, 12, 31);
        return ApiResponse.ok(lancamentos.listar(titular.id(), de, ate).stream()
                .map(LancamentoBancoResponse::de).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LancamentoBancoResponse> lancar(@Valid @RequestBody LancamentoBancoRequest r, Titular titular) {
        return ApiResponse.ok(LancamentoBancoResponse.de(
                lancamentos.lancar(titular.id(), r.data(), r.segundos(), r.descricao(), titular.quem())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> excluir(@PathVariable UUID id, Titular titular) {
        lancamentos.excluir(titular.id(), id);
        return ApiResponse.ok(null);
    }
}
