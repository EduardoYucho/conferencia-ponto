package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.application.usecase.ImportarComprovanteUseCase;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orquestra a importação de um PDF: espera o download terminar (tamanho estável e
 * arquivo destravado), lê com trava via {@link PdfParserService} e entrega a
 * data/hora ao caso de uso, que aloca a batida e notifica o front-end.
 */
@Component
public class ProcessadorComprovantePdf {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorComprovantePdf.class);

    /**
     * Arquivo sem alteração há esse tempo já terminou de ser gravado: dispensa a espera de
     * estabilização (importante na primeira leitura de uma pasta com centenas de PDFs antigos).
     */
    static final Duration ARQUIVO_ASSENTADO = Duration.ofSeconds(30);

    private final PdfParserService parser;
    private final ImportarComprovanteUseCase importar;
    private final ImportacaoPdfProperties properties;

    public ProcessadorComprovantePdf(PdfParserService parser, ImportarComprovanteUseCase importar,
                                     ImportacaoPdfProperties properties) {
        this.parser = parser;
        this.importar = importar;
        this.properties = properties;
    }

    /** Processador da pasta de um usuário (usado pelo monitor dele). */
    public ProcessadorDeComprovantes doUsuario(UUID usuarioId) {
        return arquivo -> processar(usuarioId, arquivo);
    }

    /** Resultado de um PDF enviado pela tela. {@code JA_PROCESSADO}: o mesmo arquivo já tinha sido lido. */
    public record ResultadoEnvio(String nomeArquivo, String status, String mensagem, LocalDateTime dataHoraBatida) {
    }

    /** PDF enviado pela tela ("Enviar comprovantes"): mesmas regras da pasta monitorada. */
    public ResultadoEnvio enviar(UUID usuarioId, String nomeArquivo, byte[] conteudo) {
        String nome = nomeArquivo == null || nomeArquivo.isBlank() ? "comprovante.pdf" : Path.of(nomeArquivo)
                .getFileName().toString();
        PdfParserService.ComprovanteLido lido;
        try {
            lido = parser.lerConteudo(nome, conteudo);
        } catch (IOException e) {
            return new ResultadoEnvio(nome, StatusImportacao.INVALIDO.name(), "Não foi possível ler o PDF: " + e.getMessage(),
                    null);
        }
        synchronized (this) {
            return importar.executar(usuarioId, new ImportarComprovanteUseCase.Comprovante(
                            lido.nomeArquivo(), lido.hashSha256(), lido.dataHora(), lido.conteudo()))
                    .map(r -> new ResultadoEnvio(nome, r.status().name(), r.mensagem(), lido.dataHora().orElse(null)))
                    .orElseGet(() -> new ResultadoEnvio(nome, "JA_PROCESSADO",
                            "Este comprovante já tinha sido processado.", lido.dataHora().orElse(null)));
        }
    }

    /**
     * {@code synchronized}: os monitores (um por usuário), o envio pela tela e o reprocessamento manual
     * podem disparar ao mesmo tempo; processar um arquivo por vez evita duas transações alterando o mesmo dia.
     */
    public synchronized boolean processar(UUID usuarioId, Path arquivo) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                aguardarTamanhoEstavel(arquivo);
                PdfParserService.ComprovanteLido lido = parser.ler(arquivo);
                importar.executar(usuarioId, new ImportarComprovanteUseCase.Comprovante(
                                lido.nomeArquivo(), lido.hashSha256(), lido.dataHora(), lido.conteudo()))
                        .ifPresentOrElse(
                                r -> log.info("[{}] {}: {}", r.status(), lido.nomeArquivo(), r.mensagem()),
                                () -> log.debug("{} já processado anteriormente", lido.nomeArquivo()));
                return true;
            } catch (NoSuchFileException e) {
                log.debug("{} não existe mais (arquivo temporário?)", arquivo.getFileName());
                return true;
            } catch (IOException e) {
                Path pasta = arquivo.getParent();
                if (pasta != null && !Files.isDirectory(pasta)) {
                    // a pasta (de rede) caiu: não adianta insistir agora, o monitor reconecta e tenta de novo
                    log.debug("Pasta de {} inacessível: {}", arquivo.getFileName(), e.getMessage());
                    return false;
                }
                if (tentativa >= properties.tentativasLeitura()) {
                    log.warn("Não foi possível ler {} após {} tentativas ({}); nova tentativa na próxima varredura",
                            arquivo.getFileName(), tentativa, e.getMessage());
                    return false;
                }
                log.debug("Tentativa {} para {}: {}", tentativa, arquivo.getFileName(), e.getMessage());
                dormir(properties.intervaloTentativa());
            } catch (RuntimeException e) {
                // ex.: banco fora do ar. Não marca como tratado: a próxima varredura tenta de novo.
                log.error("Falha inesperada ao importar {}", arquivo, e);
                return false;
            }
        }
    }

    /** O navegador pode criar o arquivo antes de terminar de gravar: exige tamanho > 0 e estável. */
    private void aguardarTamanhoEstavel(Path arquivo) throws IOException {
        long antes = Files.size(arquivo);
        Instant modificadoEm = Files.getLastModifiedTime(arquivo).toInstant();
        if (antes > 0 && modificadoEm.isBefore(Instant.now().minus(ARQUIVO_ASSENTADO))) {
            return;
        }
        dormir(properties.estabilizacao());
        long depois = Files.size(arquivo);
        if (antes == 0 || antes != depois) {
            throw new ArquivoEmUsoException(arquivo, "tamanho ainda mudando (%d -> %d bytes)".formatted(antes, depois));
        }
    }

    private static void dormir(Duration duracao) {
        try {
            Thread.sleep(duracao.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
