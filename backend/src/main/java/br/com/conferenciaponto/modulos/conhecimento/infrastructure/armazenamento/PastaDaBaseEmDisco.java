package br.com.conferenciaponto.modulos.conhecimento.infrastructure.armazenamento;

import br.com.conferenciaponto.modulos.conhecimento.domain.registro.PastaDaBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * As imagens dos registros em {@code <atendimento.armazenamento.diretorio>/base/<id do registro>/Imagem_01.png}: a
 * pasta que o {@code ponto backup} copia e que a retenção dos atendimentos não toca. A cópia é gravada num temporário
 * e movida no fim.
 */
@Component
public class PastaDaBaseEmDisco implements PastaDaBase {

    private static final Logger log = LoggerFactory.getLogger(PastaDaBaseEmDisco.class);
    static final String BASE = "base";

    private final Path raiz;

    public PastaDaBaseEmDisco(@Value("${atendimento.armazenamento.diretorio}") Path diretorio) {
        this.raiz = diretorio.toAbsolutePath().normalize();
    }

    @Override
    public String copiar(UUID registroId, int numero, Path origem) {
        Path pasta = pastaDo(registroId);
        String nome = String.format(Locale.ROOT, "Imagem_%02d.%s", numero, extensao(origem));
        Path destino = dentro(pasta.resolve(nome));
        try {
            Files.createDirectories(pasta);
            Path temporario = Files.createTempFile(pasta, ".copia-", ".parcial");
            try {
                Files.copy(origem, temporario, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporario);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível copiar a imagem " + numero + " para o registro " + registroId, e);
        }
        return BASE + "/" + registroId + "/" + nome;
    }

    @Override
    public Path local(String caminho) {
        return dentro(raiz.resolve(caminho));
    }

    @Override
    public void apagar(UUID registroId) {
        Path pasta = pastaDo(registroId);
        if (!Files.exists(pasta)) {
            return;
        }
        try (Stream<Path> caminhos = Files.walk(pasta)) {
            caminhos.sorted(Comparator.reverseOrder()).forEach(c -> {
                try {
                    Files.deleteIfExists(c);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Não foi possível apagar a pasta do registro {}: {}", registroId, e.toString());
        }
    }

    private Path pastaDo(UUID registroId) {
        return dentro(raiz.resolve(BASE).resolve(registroId.toString()));
    }

    /** Todo caminho fica dentro da pasta da base. */
    private Path dentro(Path caminho) {
        Path normal = caminho.toAbsolutePath().normalize();
        if (!normal.startsWith(raiz.resolve(BASE))) {
            throw new IllegalArgumentException("Caminho fora da pasta da base: " + caminho.getFileName());
        }
        return normal;
    }

    private static String extensao(Path arquivo) {
        String nome = arquivo.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String ext = ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,5}") ? ext : "img";
    }
}
