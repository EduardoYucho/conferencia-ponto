package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.PeloAppDoGemini;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * O texto pelo app do Gemini (sem a cota da API).
 *
 * <pre>
 * POST /api/v1/atendimentos/{id}/app-gemini/{tipo}/pedido     monta o pedido: {pedido, arquivos[], avisos[]}
 * GET  /api/v1/atendimentos/{id}/app-gemini/arquivos/{nome}   um arquivo para anexar no app
 * GET  /api/v1/atendimentos/{id}/app-gemini/arquivos.zip      todos, num .zip
 * POST /api/v1/atendimentos/{id}/app-gemini/{tipo}/resposta   {resposta}: o que o app respondeu vira o texto
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos/{id}/app-gemini")
public class AppDoGeminiController {

    private final PeloAppDoGemini app;
    private final ProcessarAtendimentos processamento;
    private final AcessoUsuarios acesso;

    public AppDoGeminiController(PeloAppDoGemini app, ProcessarAtendimentos processamento, AcessoUsuarios acesso) {
        this.app = app;
        this.processamento = processamento;
        this.acesso = acesso;
    }

    /** O que o app do Gemini respondeu, colado inteiro. */
    public record PedidoDeResposta(String resposta) {
    }

    @PostMapping("/{tipo}/pedido")
    public ApiResponse<PeloAppDoGemini.PedidoView> pedido(@PathVariable UUID id, @PathVariable String tipo) {
        return ApiResponse.ok(app.pedido(acesso.logado(), id, SaidasController.tipo(tipo)));
    }

    @GetMapping("/arquivos/{nome}")
    public ResponseEntity<Resource> arquivo(@PathVariable UUID id, @PathVariable String nome) {
        PeloAppDoGemini.ArquivoDoPedido arquivo = app.arquivo(acesso.logado(), id, nome);
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(arquivo.nome()).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(arquivo.nome()).build().toString())
                .body(new FileSystemResource(arquivo.local()));
    }

    /**
     * O .zip sai direto na resposta, enquanto é escrito (os vídeos podem ser grandes): nada fica na memória, e sem o
     * tempo limite das respostas assíncronas.
     */
    @GetMapping("/arquivos.zip")
    public void zip(@PathVariable UUID id, HttpServletResponse resposta) throws IOException {
        List<PeloAppDoGemini.ArquivoDoPedido> arquivos = app.arquivos(acesso.logado(), id);
        resposta.setContentType("application/zip");
        resposta.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        resposta.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("arquivos-para-o-gemini.zip").build().toString());
        PeloAppDoGemini.escreverZip(arquivos, resposta.getOutputStream());
    }

    @PostMapping("/{tipo}/resposta")
    public ApiResponse<ProgressoView> resposta(@PathVariable UUID id, @PathVariable String tipo,
                                               @RequestBody PedidoDeResposta pedido) {
        app.usarResposta(acesso.logado(), id, SaidasController.tipo(tipo), pedido == null ? null : pedido.resposta());
        return ApiResponse.ok(processamento.progresso(acesso.logado(), id));
    }
}
