package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.analise.AnaliseView;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ConsultarAnalise;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A análise de cada arquivo (só do dono do atendimento).
 *
 * <pre>
 * GET /api/v1/atendimentos/{id}/arquivos/{arquivoId}/analise                 a análise atual (ou a mais recente)
 * GET /api/v1/atendimentos/{id}/arquivos/{arquivoId}/quadros/{instanteMs}    o quadro de um passo do vídeo (PNG)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos/{id}/arquivos/{arquivoId}")
public class AnalisesController {

    private final ConsultarAnalise analises;
    private final AcessoUsuarios acesso;

    public AnalisesController(ConsultarAnalise analises, AcessoUsuarios acesso) {
        this.analises = analises;
        this.acesso = acesso;
    }

    @GetMapping("/analise")
    public ApiResponse<AnaliseView> analise(@PathVariable UUID id, @PathVariable UUID arquivoId) {
        return ApiResponse.ok(analises.doArquivo(acesso.logado(), id, arquivoId));
    }

    @GetMapping("/quadros/{instanteMs}")
    public ResponseEntity<Resource> quadro(@PathVariable UUID id, @PathVariable UUID arquivoId, @PathVariable long instanteMs) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(analises.quadro(acesso.logado(), id, arquivoId, instanteMs)));
    }
}
