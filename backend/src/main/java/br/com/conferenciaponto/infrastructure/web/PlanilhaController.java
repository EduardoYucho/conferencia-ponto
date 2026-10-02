package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.planilha.EscritorXlsx;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.EstadoPlanilhaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.PlanilhaRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Planilha de conferência: todos os meses com registro, dia a dia, com os totais de horas e o banco de horas.
 * <pre>
 * GET    /api/v1/planilha/exportar     arquivo Excel (.xlsx)                 (quem consulta os dados da pessoa)
 * GET    /api/v1/planilha              situação da planilha do Google        (quem consulta os dados da pessoa)
 * PUT    /api/v1/planilha              {link}  vincula e grava a planilha    (a própria pessoa ou o administrador)
 * POST   /api/v1/planilha/sincronizar  regrava agora                         (a própria pessoa ou o administrador)
 * DELETE /api/v1/planilha              o sistema deixa de gravar na planilha (a própria pessoa ou o administrador)
 * </pre>
 * Nas alterações, o administrador indica a pessoa com {@code ?usuario=login}.
 */
@RestController
@RequestMapping("/api/v1/planilha")
public class PlanilhaController {

    private final GerenciarPlanilhaUseCase planilhas;
    private final AcessoUsuarios acesso;
    private final Clock clock;

    public PlanilhaController(GerenciarPlanilhaUseCase planilhas, AcessoUsuarios acesso, Clock clock) {
        this.planilhas = planilhas;
        this.acesso = acesso;
        this.clock = clock;
    }

    @GetMapping("/exportar")
    public ResponseEntity<Resource> exportar(Titular titular) {
        PlanilhaConferencia planilha = planilhas.montar(titular.id());
        byte[] arquivo = EscritorXlsx.escrever(planilha);
        ContentDisposition anexo = ContentDisposition.attachment()
                .filename("conferencia-ponto-%s-%s.xlsx".formatted(nomeDeArquivo(planilha.loginPessoa()), LocalDate.now(clock)))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, anexo.toString())
                .contentType(MediaType.parseMediaType(EscritorXlsx.TIPO_MIME))
                .contentLength(arquivo.length)
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(arquivo));
    }

    @GetMapping
    public ApiResponse<EstadoPlanilhaResponse> estado(Titular titular) {
        return ApiResponse.ok(EstadoPlanilhaResponse.de(planilhas.estado(titular.id())));
    }

    @PutMapping
    public ApiResponse<EstadoPlanilhaResponse> vincular(@RequestBody PlanilhaRequest r,
                                                        @RequestParam(required = false) String usuario) {
        Usuario logado = acesso.logado();
        Usuario alvo = acesso.alvoDeEdicao(logado, usuario);
        return ApiResponse.ok(EstadoPlanilhaResponse.de(planilhas.vincular(alvo.id(), r.link(), logado.login())));
    }

    @PostMapping("/sincronizar")
    public ApiResponse<EstadoPlanilhaResponse> sincronizar(@RequestParam(required = false) String usuario) {
        Usuario alvo = acesso.alvoDeEdicao(acesso.logado(), usuario);
        return ApiResponse.ok(EstadoPlanilhaResponse.de(planilhas.sincronizarAgora(alvo.id())));
    }

    @DeleteMapping
    public ApiResponse<EstadoPlanilhaResponse> desvincular(@RequestParam(required = false) String usuario) {
        Usuario alvo = acesso.alvoDeEdicao(acesso.logado(), usuario);
        return ApiResponse.ok(EstadoPlanilhaResponse.de(planilhas.desvincular(alvo.id())));
    }

    /** Só letras, números, ponto, hífen e sublinhado (o nome vai no cabeçalho da resposta). */
    private static String nomeDeArquivo(String login) {
        String limpo = login == null ? "" : login.replaceAll("[^A-Za-z0-9._-]", "");
        return limpo.isEmpty() ? "usuario" : limpo;
    }
}
