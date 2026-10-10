package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Processamento dos atendimentos da pessoa logada e o acompanhamento ao vivo.
 *
 * <pre>
 * POST /api/v1/atendimentos/{id}/processar                          põe na fila o que falta (nesta versão, os downloads)
 * POST /api/v1/atendimentos/{id}/cancelar                           cancela as tarefas que ainda não começaram
 * POST /api/v1/atendimentos/{id}/retomar                            retoma um atendimento pausado
 * POST /api/v1/atendimentos/{id}/arquivos/{arquivoId}/tentar-de-novo  repete o download que falhou
 * GET  /api/v1/atendimentos/{id}/progresso                          a situação agora
 * GET  /api/v1/atendimentos/eventos                                 SSE: atendimento-progresso, só os do dono
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos")
public class ProcessamentoController {

    private final ProcessarAtendimentos processamento;
    private final EmissorDoGerador emissor;
    private final AcessoUsuarios acesso;

    public ProcessamentoController(ProcessarAtendimentos processamento, EmissorDoGerador emissor, AcessoUsuarios acesso) {
        this.processamento = processamento;
        this.emissor = emissor;
        this.acesso = acesso;
    }

    @PostMapping("/{id}/processar")
    public ApiResponse<ProgressoView> processar(@PathVariable UUID id) {
        return ApiResponse.ok(processamento.processar(acesso.logado(), id));
    }

    @PostMapping("/{id}/cancelar")
    public ApiResponse<ProgressoView> cancelar(@PathVariable UUID id) {
        return ApiResponse.ok(processamento.cancelar(acesso.logado(), id));
    }

    @PostMapping("/{id}/retomar")
    public ApiResponse<ProgressoView> retomar(@PathVariable UUID id) {
        return ApiResponse.ok(processamento.retomar(acesso.logado(), id));
    }

    @PostMapping("/{id}/arquivos/{arquivoId}/tentar-de-novo")
    public ApiResponse<ProgressoView> tentarDeNovo(@PathVariable UUID id, @PathVariable UUID arquivoId) {
        return ApiResponse.ok(processamento.tentarDeNovo(acesso.logado(), id, arquivoId));
    }

    @GetMapping("/{id}/progresso")
    public ApiResponse<ProgressoView> progresso(@PathVariable UUID id) {
        return ApiResponse.ok(processamento.progresso(acesso.logado(), id));
    }

    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos() {
        return emissor.conectar(acesso.logado());
    }
}
