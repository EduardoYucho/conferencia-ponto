package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.AtendimentoView;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.ResultadoDoEnvio;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.ResumoView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Os atendimentos da pessoa logada (só os próprios; quem não tem o gerador liberado recebe 403).
 *
 * <pre>
 * POST   /api/v1/atendimentos        corpo = o PDF do Digisac (application/pdf), cabeçalho X-Nome-Arquivo
 *                                    201 {criado: true, atendimento, leitura} · 200 {criado: false, ...} se o chamado já tem
 * GET    /api/v1/atendimentos        lista, do mais novo para o mais antigo
 * GET    /api/v1/atendimentos/{id}   conversa lida e anexos (atendimento de outra pessoa: 404)
 * DELETE /api/v1/atendimentos/{id}   apaga o atendimento e os arquivos
 * </pre>
 *
 * O PDF chega em fluxo (sem multipart): vai direto para um arquivo temporário, sem passar inteiro pela memória.
 */
@RestController
@RequestMapping("/api/v1/atendimentos")
public class AtendimentoController {

    private final GerenciarAtendimentos atendimentos;
    private final AcessoUsuarios acesso;

    public AtendimentoController(GerenciarAtendimentos atendimentos, AcessoUsuarios acesso) {
        this.atendimentos = atendimentos;
        this.acesso = acesso;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<ApiResponse<ResultadoDoEnvio>> enviar(
            HttpServletRequest requisicao,
            @RequestHeader(value = "X-Nome-Arquivo", required = false) String nomeDoArquivo) throws IOException {
        ResultadoDoEnvio resultado = atendimentos.receberPdf(acesso.logado(), requisicao.getInputStream(),
                requisicao.getContentLengthLong(), nome(nomeDoArquivo));
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.ok(resultado));
    }

    @GetMapping
    public ApiResponse<List<ResumoView>> listar() {
        return ApiResponse.ok(atendimentos.listar(acesso.logado()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AtendimentoView> detalhe(@PathVariable UUID id) {
        return ApiResponse.ok(atendimentos.detalhe(acesso.logado(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> apagar(@PathVariable UUID id) {
        atendimentos.apagar(acesso.logado(), id);
        return ApiResponse.ok(null);
    }

    /** O nome vem codificado (encodeURIComponent) porque cabeçalho HTTP não leva acento; só volta para a tela. */
    private static String nome(String codificado) {
        if (codificado == null || codificado.isBlank()) {
            return null;
        }
        String nome;
        try {
            nome = URLDecoder.decode(codificado, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            nome = codificado;
        }
        nome = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1).replaceAll("\\p{Cntrl}", "").strip();
        return nome.length() > 200 ? nome.substring(0, 200) : nome;
    }
}
