package br.com.conferenciaponto.infrastructure.importacao;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Confere se o computador onde o sistema roda consegue abrir a pasta escolhida. Pasta de rede fora do ar pode
 * demorar dezenas de segundos para responder: a conferência desiste em poucos segundos.
 */
@Component
public class VerificadorPasta {

    static final long LIMITE_SEGUNDOS = 6;

    public record Resultado(boolean acessivel, String aviso) {
    }

    public Resultado verificar(String pasta) {
        if (pasta == null || pasta.isBlank()) {
            return new Resultado(false, "Sem pasta: envie os comprovantes pela tela (\"Enviar PDFs\").");
        }
        Path caminho = GerenciadorMonitoresPdf.caminho(pasta).orElse(null);
        if (caminho == null) {
            return new Resultado(false, "O caminho da pasta é inválido.");
        }
        CompletableFuture<Boolean> conferencia = CompletableFuture.supplyAsync(
                () -> Files.isDirectory(caminho) && Files.isReadable(caminho));
        try {
            boolean ok = conferencia.get(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
            return ok
                    ? new Resultado(true, null)
                    : new Resultado(false, "O computador onde o sistema roda não encontrou essa pasta. Se ela fica "
                    + "em outro computador, compartilhe-a na rede e use o caminho de rede (ex.: \\\\NOME-DO-PC\\Ponto).");
        } catch (TimeoutException e) {
            conferencia.cancel(true);
            return new Resultado(false, "A pasta não respondeu em %d segundos (rede fora do ar?). O monitor continua "
                    .formatted(LIMITE_SEGUNDOS) + "tentando sozinho.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resultado(false, "A conferência da pasta foi interrompida.");
        } catch (ExecutionException e) {
            return new Resultado(false, "Não foi possível abrir a pasta: " + e.getCause().getMessage());
        }
    }
}
