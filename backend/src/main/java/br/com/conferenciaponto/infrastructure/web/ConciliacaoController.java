package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.application.usecase.ConferirConciliacaoUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarConciliacaoUseCase;
import br.com.conferenciaponto.application.usecase.ImportarRelatorioRhUseCase;
import br.com.conferenciaponto.application.usecase.ResolverDivergenciaUseCase;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.AceiteLoteRequest;
import br.com.conferenciaponto.infrastructure.web.dto.AceiteLoteResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ConciliacaoResumoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.DivergenciaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ManterLocalRequest;
import br.com.conferenciaponto.infrastructure.web.dto.RelatorioRhResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Conciliação com o relatório de banco de horas do RH.
 * <pre>
 * POST   /api/v1/conciliacoes                               envia o PDF (campo "arquivo") → 202; confere em 2º plano
 * GET    /api/v1/conciliacoes/resumo                        relatórios (com comparativo de saldo) e pendências por tipo
 * GET    /api/v1/conciliacoes/divergencias?status=&inicio=&fim=   status: PENDENTE (padrão), ACEITO_RH, MANTIDO_LOCAL,
 *                                                           RESOLVIDA ou TODAS
 * POST   /api/v1/conciliacoes/divergencias/{id}/aceitar     "Aceitar dados do RH"
 * POST   /api/v1/conciliacoes/divergencias/{id}/manter      "Manter dados locais" {observacao?}
 * POST   /api/v1/conciliacoes/divergencias/{id}/reabrir     volta a decisão para pendente
 * POST   /api/v1/conciliacoes/divergencias/aceitar-lote     {tipos[], inicio?, fim?}
 * POST   /api/v1/conciliacoes/reconferir                    confere de novo todas as datas
 * DELETE /api/v1/conciliacoes/relatorios/{id}               remove um relatório enviado por engano
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/conciliacoes")
public class ConciliacaoController {

    private final ImportarRelatorioRhUseCase importar;
    private final ConsultarConciliacaoUseCase consultar;
    private final ResolverDivergenciaUseCase resolver;
    private final ConferirConciliacaoUseCase conferir;

    public ConciliacaoController(ImportarRelatorioRhUseCase importar, ConsultarConciliacaoUseCase consultar,
                                 ResolverDivergenciaUseCase resolver, ConferirConciliacaoUseCase conferir) {
        this.importar = importar;
        this.consultar = consultar;
        this.resolver = resolver;
        this.conferir = conferir;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<RelatorioRhResponse> enviar(@RequestPart("arquivo") MultipartFile arquivo, Titular titular)
            throws IOException {
        return ApiResponse.ok(RelatorioRhResponse.de(importar.receber(titular.id(), arquivo.getOriginalFilename(),
                arquivo.getBytes(), titular.quem())));
    }

    @GetMapping("/resumo")
    public ApiResponse<ConciliacaoResumoResponse> resumo(Titular titular) {
        return ApiResponse.ok(ConciliacaoResumoResponse.de(consultar.resumo(titular.id())));
    }

    @GetMapping("/divergencias")
    public ApiResponse<List<DivergenciaResponse>> divergencias(
            @RequestParam(defaultValue = "PENDENTE") String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
            Titular titular) {
        StatusDivergencia filtro;
        try {
            filtro = "TODAS".equalsIgnoreCase(status) ? null : StatusDivergencia.valueOf(status.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RegraNegocioException("STATUS_INVALIDO",
                    "Situação inválida: use PENDENTE, ACEITO_RH, MANTIDO_LOCAL, RESOLVIDA ou TODAS.");
        }
        return ApiResponse.ok(consultar.divergencias(titular.id(), filtro, inicio, fim).stream()
                .map(DivergenciaResponse::de).toList());
    }

    @PostMapping("/divergencias/{id}/aceitar")
    public ApiResponse<DivergenciaResponse> aceitar(@PathVariable UUID id, Titular titular) {
        resolver.aceitarRh(titular.id(), id, titular.quem(), titular.logado().isAdmin());
        return ApiResponse.ok(uma(titular, id));
    }

    @PostMapping("/divergencias/{id}/manter")
    public ApiResponse<DivergenciaResponse> manter(@PathVariable UUID id,
                                                   @Valid @RequestBody(required = false) ManterLocalRequest r,
                                                   Titular titular) {
        resolver.manterLocal(titular.id(), id, r == null ? null : r.observacao(), titular.quem());
        return ApiResponse.ok(uma(titular, id));
    }

    @PostMapping("/divergencias/{id}/reabrir")
    public ApiResponse<DivergenciaResponse> reabrir(@PathVariable UUID id, Titular titular) {
        resolver.reabrir(titular.id(), id);
        return ApiResponse.ok(uma(titular, id));
    }

    @PostMapping("/divergencias/aceitar-lote")
    public ApiResponse<AceiteLoteResponse> aceitarLote(@Valid @RequestBody AceiteLoteRequest r, Titular titular) {
        return ApiResponse.ok(AceiteLoteResponse.de(resolver.aceitarEmLote(titular.id(), r.tipos(), r.inicio(),
                r.fim(), titular.quem(), titular.logado().isAdmin())));
    }

    @PostMapping("/reconferir")
    public ApiResponse<ConciliacaoResumoResponse> reconferir(Titular titular) {
        conferir.conferirTudo(titular.id());
        return ApiResponse.ok(ConciliacaoResumoResponse.de(consultar.resumo(titular.id())));
    }

    @DeleteMapping("/relatorios/{id}")
    public ApiResponse<Void> excluirRelatorio(@PathVariable UUID id, Titular titular) {
        importar.excluir(titular.id(), id);
        return ApiResponse.ok(null);
    }

    private DivergenciaResponse uma(Titular titular, UUID id) {
        return consultar.divergencias(titular.id(), null, null, null).stream()
                .filter(v -> v.divergencia().id().equals(id))
                .findFirst().map(DivergenciaResponse::de).orElse(null);
    }
}
