package br.com.receptorbackup.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.receptorbackup.coleta.ErroDeColeta;
import br.com.receptorbackup.coleta.Pedidos;
import br.com.receptorbackup.coleta.ServicoDeColetas;
import br.com.receptorbackup.config.Propriedades;
import jakarta.servlet.http.HttpServletRequest;

/**
 * O que o túnel publica: a página de coleta ({@code /c/<token>}) e a API dela. Os arquivos da página ficam em
 * {@code /c/_/...} (estáticos). O token na URL é a única credencial: 32 bytes aleatórios, um por coleta.
 */
@RestController
public class ColetaPublicaController {

    private final ServicoDeColetas servico;
    private final Propriedades propriedades;

    public ColetaPublicaController(ServicoDeColetas servico, Propriedades propriedades) {
        this.servico = servico;
        this.propriedades = propriedades;
    }

    @GetMapping(value = "/c/{token:[A-Za-z0-9_-]{43}}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> pagina(@PathVariable String token) throws IOException {
        servico.info(token);
        try (InputStream entrada = new ClassPathResource("static/c/_/coleta.html").getInputStream()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/c/{token}/api/coleta")
    public ResponseEntity<Pedidos.Info> coleta(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.info(token));
    }

    @PostMapping("/c/{token}/api/geracao")
    public Pedidos.Info geracao(@PathVariable String token, @RequestBody Pedidos.Geracao pedido) {
        return servico.iniciarGeracao(token, pedido);
    }

    @PutMapping("/c/{token}/api/partes/{seq}")
    public Pedidos.ParteAceita parte(@PathVariable String token, @PathVariable long seq,
            @RequestParam String geracao, HttpServletRequest requisicao) throws IOException {
        long limite = propriedades.maxParteBytes() + 16L * 1024 * 1024;
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
        return servico.receberParte(token, seq, geracao, corpo);
    }

    @PostMapping("/c/{token}/api/progresso")
    public ResponseEntity<Void> progresso(@PathVariable String token, @RequestBody Pedidos.Progresso pedido) {
        servico.progresso(token, pedido);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/c/{token}/api/concluir")
    public Pedidos.Info concluir(@PathVariable String token, @RequestBody Pedidos.Conclusao pedido) {
        return servico.concluir(token, pedido);
    }
}
