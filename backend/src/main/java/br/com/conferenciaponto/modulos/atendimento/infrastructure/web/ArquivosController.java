package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.arquivo.ArquivoView;
import br.com.conferenciaponto.modulos.atendimento.application.arquivo.GerenciarArquivos;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.ResultadoDoEnvio;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Arquivos dos atendimentos da pessoa logada. Os envios chegam em fluxo (o corpo é o arquivo, sem multipart).
 *
 * <pre>
 * PUT    /api/v1/atendimentos/{id}/arquivos?origem=ligacao|video|print_extra&amp;nome=&amp;modificadoEm=   envia um extra
 * PUT    /api/v1/atendimentos/{id}/arquivos/{arquivoId}/conteudo   envio à mão de um anexo que falhou (X-Nome-Arquivo)
 * DELETE /api/v1/atendimentos/{id}/arquivos/{arquivoId}            tira um arquivo
 * PUT    /api/v1/atendimentos/{id}/pdf                             PDF novo do mesmo chamado: renova os links que faltam
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos/{id}")
public class ArquivosController {

    private final GerenciarArquivos arquivos;
    private final GerenciarAtendimentos atendimentos;
    private final AcessoUsuarios acesso;

    public ArquivosController(GerenciarArquivos arquivos, GerenciarAtendimentos atendimentos, AcessoUsuarios acesso) {
        this.arquivos = arquivos;
        this.atendimentos = atendimentos;
        this.acesso = acesso;
    }

    @PutMapping("/arquivos")
    public ResponseEntity<ApiResponse<ArquivoView>> enviarExtra(@PathVariable UUID id, HttpServletRequest requisicao,
                                                                @RequestParam(required = false) String origem,
                                                                @RequestParam(required = false) String nome,
                                                                @RequestParam(required = false) String modificadoEm)
            throws IOException {
        ArquivoView view = arquivos.enviarExtra(acesso.logado(), id, origem, nome, modificadoEm, requisicao.getInputStream(),
                requisicao.getContentLengthLong());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view));
    }

    @PutMapping("/arquivos/{arquivoId}/conteudo")
    public ApiResponse<ArquivoView> enviarConteudo(@PathVariable UUID id, @PathVariable UUID arquivoId,
                                                   HttpServletRequest requisicao,
                                                   @RequestHeader(value = "X-Nome-Arquivo", required = false) String nome)
            throws IOException {
        return ApiResponse.ok(arquivos.enviarConteudo(acesso.logado(), id, arquivoId, decodificar(nome),
                requisicao.getInputStream(), requisicao.getContentLengthLong()));
    }

    @DeleteMapping("/arquivos/{arquivoId}")
    public ApiResponse<Void> tirar(@PathVariable UUID id, @PathVariable UUID arquivoId) {
        arquivos.tirar(acesso.logado(), id, arquivoId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/pdf")
    public ApiResponse<ResultadoDoEnvio> renovarPdf(@PathVariable UUID id, HttpServletRequest requisicao,
                                                    @RequestHeader(value = "X-Nome-Arquivo", required = false) String nome)
            throws IOException {
        return ApiResponse.ok(atendimentos.renovarPdf(acesso.logado(), id, requisicao.getInputStream(),
                requisicao.getContentLengthLong(), decodificar(nome)));
    }

    /** O nome vem codificado (encodeURIComponent) porque cabeçalho HTTP não leva acento. */
    private static String decodificar(String codificado) {
        if (codificado == null || codificado.isBlank()) {
            return null;
        }
        try {
            return URLDecoder.decode(codificado, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return codificado;
        }
    }
}
