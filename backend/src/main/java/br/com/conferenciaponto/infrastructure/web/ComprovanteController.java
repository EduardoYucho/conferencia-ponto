package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.BaixarComprovanteUseCase;
import br.com.conferenciaponto.application.usecase.DesfazerImportacaoUseCase;
import br.com.conferenciaponto.application.view.ArquivoComprovanteView;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * GET  /api/comprovantes/{id}/download (também em /api/v1/...)  o PDF arquivado como anexo (imutável, conferido pelo
 *      hash antes do envio; o hash vira o ETag)
 * POST /api/v1/comprovantes/{id}/desfazer {motivo}   o comprovante não é meu: sai do meu ponto (o próprio ponto)
 * POST /api/v1/comprovantes/{id}/mover {usuario, motivo}   vai para o ponto da pessoa certa (ponto.equipe.configurar)
 * </pre>
 */
@RestController
public class ComprovanteController {

    private final BaixarComprovanteUseCase baixar;
    private final DesfazerImportacaoUseCase desfazer;
    private final AcessoUsuarios acesso;

    public ComprovanteController(BaixarComprovanteUseCase baixar, DesfazerImportacaoUseCase desfazer,
                                 AcessoUsuarios acesso) {
        this.baixar = baixar;
        this.desfazer = desfazer;
        this.acesso = acesso;
    }

    public record DesfazerRequest(@Size(max = 200, message = "O motivo pode ter no máximo 200 caracteres.") String motivo) {
    }

    public record MoverRequest(@NotBlank(message = "Escolha a pessoa que vai receber o comprovante.") String usuario,
                               @Size(max = 200, message = "O motivo pode ter no máximo 200 caracteres.") String motivo) {
    }

    /**
     * @param data o dia de onde o comprovante saiu
     */
    public record ImportacaoDesfeitaResponse(String mensagem, LocalDate data, boolean diaRemovido) {
    }

    @PostMapping("/api/v1/comprovantes/{id}/desfazer")
    public ApiResponse<ImportacaoDesfeitaResponse> desfazer(@PathVariable UUID id,
                                                            @Valid @RequestBody(required = false) DesfazerRequest corpo,
                                                            Titular titular) {
        DesfazerImportacaoUseCase.Resultado r = desfazer.desfazer(titular.id(), id,
                corpo == null ? null : corpo.motivo(), titular.quem());
        return ApiResponse.ok(resposta(r));
    }

    @PostMapping("/api/v1/comprovantes/{id}/mover")
    public ApiResponse<ImportacaoDesfeitaResponse> mover(@PathVariable UUID id, @Valid @RequestBody MoverRequest corpo) {
        Usuario logado = acesso.logado();
        Usuario destino = acesso.alvoDeEdicao(logado, corpo.usuario());
        return ApiResponse.ok(resposta(desfazer.mover(id, destino.id(), corpo.motivo(), logado.login())));
    }

    private static ImportacaoDesfeitaResponse resposta(DesfazerImportacaoUseCase.Resultado r) {
        return new ImportacaoDesfeitaResponse(r.mensagem(), r.data(), r.dia() == null);
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
