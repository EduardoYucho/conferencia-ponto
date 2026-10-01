package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.BaixarComprovanteUseCase;
import br.com.conferenciaponto.application.view.ArquivoComprovanteView;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * GET /api/comprovantes/{id}/download (também em /api/v1/...): devolve o PDF arquivado como anexo.
 * O conteúdo é imutável e conferido pelo hash antes do envio; o hash vira o ETag.
 */
@RestController
public class ComprovanteController {

    private final BaixarComprovanteUseCase baixar;

    public ComprovanteController(BaixarComprovanteUseCase baixar) {
        this.baixar = baixar;
    }

    @GetMapping({"/api/comprovantes/{id}/download", "/api/v1/comprovantes/{id}/download"})
    public ResponseEntity<Resource> download(@PathVariable UUID id, Titular titular) {
        ArquivoComprovanteView arquivo = baixar.executar(titular.id(), id);
        // nome sempre ASCII (comprovante_<data>_<tipo>_<hhmmss>.pdf): dispensa a codificação RFC 2047/5987
        ContentDisposition anexo = ContentDisposition.attachment()
                .filename(arquivo.comprovante().nomeDownload())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, anexo.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(arquivo.conteudo().length)
                .eTag("\"" + arquivo.comprovante().hashSha256() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(new ByteArrayResource(arquivo.conteudo()));
    }
}
