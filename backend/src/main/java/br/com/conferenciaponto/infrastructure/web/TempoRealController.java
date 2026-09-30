package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConsultarImportacoesUseCase;
import br.com.conferenciaponto.infrastructure.config.AsyncConfig;
import br.com.conferenciaponto.infrastructure.importacao.DiretorioPontoWatcher;
import br.com.conferenciaponto.infrastructure.importacao.ImportacaoPdfProperties;
import br.com.conferenciaponto.infrastructure.importacao.ProcessadorDeComprovantes;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ComprovanteResponse;
import br.com.conferenciaponto.infrastructure.web.dto.MonitoramentoResponse;
import br.com.conferenciaponto.infrastructure.web.sse.EmissorEventosSse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * <pre>
 * GET  /api/v1/eventos                 stream SSE (text/event-stream) com as atualizações da jornada
 * GET  /api/v1/importacoes              estado do monitor de PDFs + últimos comprovantes processados
 * POST /api/v1/importacoes/reprocessar  varre a pasta monitorada de novo (ADMIN/USER)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class TempoRealController {

    private final EmissorEventosSse emissor;
    private final ConsultarImportacoesUseCase consultarImportacoes;
    private final ImportacaoPdfProperties properties;
    private final ObjectProvider<DiretorioPontoWatcher> watcher;
    private final ProcessadorDeComprovantes processador;
    private final TaskExecutor executor;

    public TempoRealController(EmissorEventosSse emissor, ConsultarImportacoesUseCase consultarImportacoes,
                               ImportacaoPdfProperties properties, ObjectProvider<DiretorioPontoWatcher> watcher,
                               ProcessadorDeComprovantes processador,
                               @Qualifier(AsyncConfig.EXECUTOR_REPROCESSAMENTO) TaskExecutor executor) {
        this.emissor = emissor;
        this.consultarImportacoes = consultarImportacoes;
        this.properties = properties;
        this.watcher = watcher;
        this.processador = processador;
        this.executor = executor;
    }

    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos() {
        return emissor.conectar(monitoramento(List.of()));
    }

    @GetMapping("/importacoes")
    public ApiResponse<MonitoramentoResponse> importacoes(@RequestParam(defaultValue = "20") int limite) {
        List<ComprovanteResponse> recentes = consultarImportacoes.recentes(limite).stream()
                .map(ComprovanteResponse::de)
                .toList();
        return ApiResponse.ok(monitoramento(recentes));
    }

    /** Processamento de arquivos: reimporta o que houver na pasta (duplicados são ignorados pelo hash). */
    @PostMapping("/importacoes/reprocessar")
    public ResponseEntity<ApiResponse<MonitoramentoResponse>> reprocessar() {
        executor.execute(() -> processador.processarDiretorio(properties.diretorioMonitorado()));
        return ResponseEntity.accepted().body(ApiResponse.ok(monitoramento(List.of())));
    }

    private MonitoramentoResponse monitoramento(List<ComprovanteResponse> recentes) {
        DiretorioPontoWatcher monitor = watcher.getIfAvailable();
        return monitor != null
                ? MonitoramentoResponse.de(monitor.getEstado(), recentes)
                : MonitoramentoResponse.desabilitado(properties.diretorioMonitorado().toString(), recentes);
    }
}
