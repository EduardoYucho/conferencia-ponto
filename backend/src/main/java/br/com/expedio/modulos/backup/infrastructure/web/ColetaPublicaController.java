package br.com.expedio.modulos.backup.infrastructure.web;

import br.com.expedio.modulos.backup.domain.ErroDeColeta;
import br.com.expedio.modulos.backup.domain.Pedidos;
import br.com.expedio.modulos.backup.infrastructure.coleta.ServicoDeColetas;
import br.com.expedio.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;
import br.com.expedio.modulos.backup.infrastructure.tunel.ServicoDeTunel;
import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A página de coleta, aberta no navegador do cliente pelo link (sem login: o token na URL é a credencial, 32 bytes
 * aleatórios, um por coleta). É o que o túnel publica.
 *
 * <pre>
 * GET  /c/{token}                       a página (os arquivos dela ficam em /c/_/...)
 * GET  /c/{token}/api/coleta            o que mostrar e de onde continuar
 * POST /c/{token}/api/geracao           começo (ou recomeço) da leitura
 * PUT  /c/{token}/api/partes/{seq}      um pedaço (envelope "RBK1" + quadro zstd)
 * POST /c/{token}/api/progresso         andamento da leitura (para a tela do sistema)
 * POST /c/{token}/api/concluir          todos os pedaços enviados: fecha o arquivo e confere
 * </pre>
 * As falhas respondem {@code {codigo, mensagem}} (veja {@link TratadorDeErrosDaColeta}).
 */
@RestController
public class ColetaPublicaController {

    private final ServicoDeColetas servico;
    private final ConfiguracaoDoBackup configuracao;
    private final ServicoDeTunel tunel;

    public ColetaPublicaController(ServicoDeColetas servico, ConfiguracaoDoBackup configuracao, ServicoDeTunel tunel) {
        this.servico = servico;
        this.configuracao = configuracao;
        this.tunel = tunel;
    }

    @GetMapping(value = "/c/{token:[A-Za-z0-9_-]{43}}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> pagina(@PathVariable String token, HttpServletRequest requisicao) throws IOException {
        // Aberta pelo endereço da rede (http://<ip>:8080/c/...): ali o navegador não deixa ler pastas (não é https).
        // Com o túnel no ar, vai para o mesmo link pelo endereço seguro dele.
        if (pelaRede(requisicao)) {
            String endereco = tunel.situacao().endereco();
            if (endereco != null) {
                return ResponseEntity.status(302).location(URI.create(endereco + "/c/" + token)).build();
            }
        }
        // sempre a página: com link vencido ou apagado, ela mesma mostra o aviso (e não um JSON)
        try (InputStream entrada = new ClassPathResource("backup/coleta/coleta.html").getInputStream()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Pedido pela porta de sempre (não pela do túnel), em http e com endereço de rede (não localhost): o navegador lá
     * não lê pastas.
     */
    boolean pelaRede(HttpServletRequest requisicao) {
        String host = requisicao.getServerName();
        return requisicao.getLocalPort() != configuracao.portaPublica()
                && !requisicao.isSecure()
                && !"https".equalsIgnoreCase(requisicao.getHeader("X-Forwarded-Proto"))
                && !"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host) && !"[::1]".equals(host)
                && !host.toLowerCase().endsWith(".localhost");
    }

    /** O segredo de sessão da página (veja ServicoDeColetas#exigirSessao). */
    static final String SESSAO = "X-Coleta-Sessao";

    @GetMapping("/c/{token}/api/coleta")
    public ResponseEntity<Pedidos.Info> coleta(@PathVariable String token,
                                               @RequestHeader(value = SESSAO, required = false) String sessao) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.info(token, sessao));
    }

    @PostMapping("/c/{token}/api/geracao")
    public Pedidos.Info geracao(@PathVariable String token, @RequestBody Pedidos.Geracao pedido,
                                @RequestHeader(value = SESSAO, required = false) String sessao) {
        return servico.iniciarGeracao(token, pedido, sessao);
    }

    @PutMapping("/c/{token}/api/partes/{seq}")
    public Pedidos.ParteAceita parte(@PathVariable String token, @PathVariable long seq,
                                     @RequestParam String geracao,
                                     @RequestHeader(value = SESSAO, required = false) String sessao,
                                     HttpServletRequest requisicao) throws IOException {
        // link, sessão e geração conferidos ANTES de ler o corpo (que pode ter dezenas de MB)
        servico.prepararEnvio(token, geracao, sessao);
        long limite = configuracao.maxParteBytes() + 16L * 1024 * 1024;
        long declarado = requisicao.getContentLengthLong();
        if (declarado > limite) {
            throw new ErroDeColeta(413, "PEDACO_GRANDE", "Pedaço maior que o permitido.");
        }
        byte[] corpo;
        try (InputStream entrada = requisicao.getInputStream()) {
            corpo = entrada.readNBytes((int) Math.min(limite + 1, Integer.MAX_VALUE - 8));
        }
        if (corpo.length > limite) {
            throw new ErroDeColeta(413, "PEDACO_GRANDE", "Pedaço maior que o permitido.");
        }
        if (declarado >= 0 && corpo.length != declarado) {
            throw new ErroDeColeta(400, "PEDACO_INCOMPLETO", "O pedaço chegou pela metade; mande de novo.");
        }
        return servico.receberParte(token, seq, geracao, sessao, corpo);
    }

    @PostMapping("/c/{token}/api/progresso")
    public ResponseEntity<Void> progresso(@PathVariable String token, @RequestBody Pedidos.Progresso pedido,
                                          @RequestHeader(value = SESSAO, required = false) String sessao) {
        servico.progresso(token, pedido, sessao);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/c/{token}/api/concluir")
    public Pedidos.Info concluir(@PathVariable String token, @RequestBody Pedidos.Conclusao pedido,
                                 @RequestHeader(value = SESSAO, required = false) String sessao) {
        return servico.concluir(token, pedido, sessao);
    }
}
