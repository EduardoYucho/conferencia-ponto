package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.PeloAppDoGemini;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * O texto pelo Gem do Gemini (sem a cota da API).
 *
 * <pre>
 * GET  /api/v1/atendimentos/app-gemini/instrucoes-do-gem       {versao, texto}: as instruções para criar o Gem
 * POST /api/v1/atendimentos/{id}/app-gemini/pacote             monta o pacote: {arquivos[], avisos[], ...}
 * GET  /api/v1/atendimentos/{id}/app-gemini/arquivos/{nome}    um arquivo do pacote
 * POST /api/v1/atendimentos/{id}/app-gemini/{tipo}/resposta    {resposta}: o que o Gem respondeu vira o texto
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos")
public class AppDoGeminiController {

    private final PeloAppDoGemini app;
    private final ProcessarAtendimentos processamento;
    private final AcessoUsuarios acesso;

    public AppDoGeminiController(PeloAppDoGemini app, ProcessarAtendimentos processamento, AcessoUsuarios acesso) {
        this.app = app;
        this.processamento = processamento;
        this.acesso = acesso;
    }

    /** O que o Gemini respondeu, colado inteiro. */
    public record PedidoDeResposta(String resposta) {
    }

    @GetMapping("/app-gemini/instrucoes-do-gem")
    public ApiResponse<PeloAppDoGemini.InstrucoesDoGem> instrucoes() {
        return ApiResponse.ok(app.instrucoesDoGem());
    }

    @PostMapping("/{id}/app-gemini/pacote")
    public ApiResponse<PeloAppDoGemini.PacoteView> pacote(@PathVariable UUID id) {
        return ApiResponse.ok(app.pacote(acesso.logado(), id));
    }

    @GetMapping("/{id}/app-gemini/arquivos/{nome}")
    public ResponseEntity<Resource> arquivo(@PathVariable UUID id, @PathVariable String nome) {
        PeloAppDoGemini.ArquivoDoPacote arquivo = app.arquivo(acesso.logado(), id, nome);
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(arquivo.nome()).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(arquivo.nome()).build().toString())
                .body(new FileSystemResource(arquivo.local()));
    }

    @PostMapping("/{id}/app-gemini/{tipo}/resposta")
    public ApiResponse<ProgressoView> resposta(@PathVariable UUID id, @PathVariable String tipo,
                                               @RequestBody PedidoDeResposta pedido) {
        app.usarResposta(acesso.logado(), id, SaidasController.tipo(tipo), pedido == null ? null : pedido.resposta());
        return ApiResponse.ok(processamento.progresso(acesso.logado(), id));
    }
}
