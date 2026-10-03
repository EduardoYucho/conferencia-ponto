package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConsultarImportacoesUseCase;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.config.AsyncConfig;
import br.com.conferenciaponto.infrastructure.importacao.GerenciadorMonitoresPdf;
import br.com.conferenciaponto.infrastructure.importacao.ProcessadorComprovantePdf;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ComprovanteResponse;
import br.com.conferenciaponto.infrastructure.web.dto.MonitoramentoResponse;
import br.com.conferenciaponto.infrastructure.web.sse.EmissorEventosSse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * GET  /api/v1/eventos                 stream SSE (text/event-stream) com as atualizações em tempo real
 * GET  /api/v1/importacoes              estado do monitor da pasta do usuário + últimos comprovantes processados
 * POST /api/v1/importacoes/reprocessar  varre a pasta do usuário de novo
 * POST /api/v1/importacoes/enviar       comprovantes PDF enviados pela tela (multipart "arquivos")
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class TempoRealController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TempoRealController.class);

    /** Arquivos por envio (a tela manda um por vez; o limite protege a API). */
    static final int MAXIMO_ARQUIVOS_POR_ENVIO = 50;

    private final EmissorEventosSse emissor;
    private final ConsultarImportacoesUseCase consultarImportacoes;
    private final GerenciadorMonitoresPdf monitores;
    private final ProcessadorComprovantePdf processador;
    private final UsuarioRepository usuarios;
    private final AcessoUsuarios acesso;
    private final TaskExecutor executor;

    public TempoRealController(EmissorEventosSse emissor, ConsultarImportacoesUseCase consultarImportacoes,
                               GerenciadorMonitoresPdf monitores, ProcessadorComprovantePdf processador,
                               UsuarioRepository usuarios, AcessoUsuarios acesso,
                               @Qualifier(AsyncConfig.EXECUTOR_REPROCESSAMENTO) TaskExecutor executor) {
        this.emissor = emissor;
        this.consultarImportacoes = consultarImportacoes;
        this.monitores = monitores;
        this.processador = processador;
        this.usuarios = usuarios;
        this.acesso = acesso;
        this.executor = executor;
    }

    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos() {
        Usuario logado = acesso.logado();
        return emissor.conectar(logado, logado.isTitular() ? monitoramento(logado, List.of()) : null);
    }

    @GetMapping("/importacoes")
    public ApiResponse<MonitoramentoResponse> importacoes(@RequestParam(defaultValue = "20") int limite,
                                                          Titular titular) {
        List<ComprovanteResponse> recentes = consultarImportacoes.recentes(titular.id(), limite).stream()
                .map(ComprovanteResponse::de)
                .toList();
        return ApiResponse.ok(monitoramento(usuario(titular.id()), recentes));
    }

    /** Reimporta o que houver na pasta do usuário (duplicados são ignorados pelo hash). */
    @PostMapping("/importacoes/reprocessar")
    public ResponseEntity<ApiResponse<MonitoramentoResponse>> reprocessar(Titular titular) {
        Usuario usuario = usuario(titular.id());
        Path pasta = Optional.ofNullable(usuario.pastaComprovantes())
                .flatMap(GerenciadorMonitoresPdf::caminho)
                .orElseThrow(() -> new RegraNegocioException("SEM_PASTA",
                        "Configure a pasta dos comprovantes em \"Minha conta\" ou envie os PDFs pela tela."));
        executor.execute(() -> processador.doUsuario(usuario.id()).processarDiretorio(pasta));
        return ResponseEntity.accepted().body(ApiResponse.ok(monitoramento(usuario, List.of())));
    }

    /** Comprovantes enviados pela tela (arrastar e soltar): mesmas regras da pasta monitorada. */
    @PostMapping(path = "/importacoes/enviar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<ProcessadorComprovantePdf.ResultadoEnvio>> enviar(
            @RequestPart("arquivos") List<MultipartFile> arquivos, Titular titular) {
        if (arquivos == null || arquivos.isEmpty()) {
            throw new RegraNegocioException("SEM_ARQUIVOS", "Selecione os comprovantes em PDF.");
        }
        if (arquivos.size() > MAXIMO_ARQUIVOS_POR_ENVIO) {
            throw new RegraNegocioException("ARQUIVOS_DEMAIS",
                    "Envie no máximo %d arquivos por vez.".formatted(MAXIMO_ARQUIVOS_POR_ENVIO));
        }
        List<ProcessadorComprovantePdf.ResultadoEnvio> resultados = new ArrayList<>();
        for (MultipartFile arquivo : arquivos) {
            String nome = arquivo.getOriginalFilename();
            if (nome == null || !nome.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                resultados.add(new ProcessadorComprovantePdf.ResultadoEnvio(nome, "INVALIDO",
                        "Só arquivos PDF são aceitos.", null));
                continue;
            }
            try {
                resultados.add(processador.enviar(titular.id(), nome, arquivo.getBytes()));
            } catch (IOException | RuntimeException e) {
                // um arquivo com problema não impede os outros
                log.error("Falha ao importar o comprovante \"{}\" enviado pela tela", nome, e);
                resultados.add(new ProcessadorComprovantePdf.ResultadoEnvio(nome, "ERRO",
                        "Não foi possível importar este arquivo agora. Tente enviá-lo de novo.", null));
            }
        }
        return ApiResponse.ok(resultados);
    }

    private Usuario usuario(UUID id) {
        return usuarios.buscarPorId(id).orElseThrow();
    }

    private MonitoramentoResponse monitoramento(Usuario usuario, List<ComprovanteResponse> recentes) {
        if (!monitores.habilitado()) {
            return MonitoramentoResponse.desabilitado(usuario.pastaComprovantes(), recentes);
        }
        return monitores.estado(usuario.id())
                .map(estado -> MonitoramentoResponse.de(estado, recentes))
                .orElseGet(() -> MonitoramentoResponse.semMonitor(usuario.pastaComprovantes(), recentes));
    }
}
