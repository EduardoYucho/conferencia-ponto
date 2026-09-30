package br.com.conferenciaponto.infrastructure.armazenamento;

import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Armazenamento de comprovantes no sistema de arquivos local.
 *
 * <ul>
 *   <li>Estrutura: {@code <raiz>/<ano>/<mês>/comprovante_<uuid>.pdf} — o UUID evita colisões e
 *       o nome original do download não interfere no armazenamento.</li>
 *   <li>Gravação atômica: escreve num temporário na mesma pasta, força o flush em disco e
 *       renomeia. Nunca fica um PDF pela metade com o nome final.</li>
 *   <li>O arquivo final é marcado como somente leitura (proteção contra alteração acidental);
 *       a integridade é conferida pelo hash a cada download.</li>
 *   <li>Se a transação que registrou o comprovante sofrer rollback, o arquivo é removido.</li>
 *   <li>Toda resolução de caminho é confinada à raiz (bloqueia path traversal).</li>
 * </ul>
 */
@Service
public class ArmazenamentoLocalComprovantes implements ArmazenamentoComprovantes {

    private static final Logger log = LoggerFactory.getLogger(ArmazenamentoLocalComprovantes.class);

    private final Path raiz;
    private final String urlBase;

    @Autowired
    public ArmazenamentoLocalComprovantes(ArmazenamentoProperties properties) {
        this(properties.diretorioRaiz(), properties.urlBase());
    }

    public ArmazenamentoLocalComprovantes(Path raiz, String urlBase) {
        this.raiz = raiz.toAbsolutePath().normalize();
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        try {
            Files.createDirectories(this.raiz);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível criar o armazenamento em " + this.raiz, e);
        }
        log.info("Comprovantes arquivados em {}", this.raiz);
    }

    @Override
    public String armazenar(UUID id, byte[] conteudo, LocalDate dataReferencia) throws IOException {
        String relativo = "%04d/%02d/comprovante_%s.pdf".formatted(
                dataReferencia.getYear(), dataReferencia.getMonthValue(), id);
        Path destino = resolver(relativo);
        Files.createDirectories(destino.getParent());

        Path temporario = Files.createTempFile(destino.getParent(), "upload-", ".tmp");
        try {
            try (FileChannel canal = FileChannel.open(temporario, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                canal.write(ByteBuffer.wrap(conteudo));
                canal.force(true);
            }
            mover(temporario, destino);
        } finally {
            Files.deleteIfExists(temporario);
        }

        if (!destino.toFile().setReadOnly()) {
            log.debug("Não foi possível marcar {} como somente leitura", destino);
        }
        removerSeHouverRollback(destino);
        return relativo;
    }

    @Override
    public byte[] ler(String caminhoArquivo) throws IOException {
        return Files.readAllBytes(resolver(caminhoArquivo));
    }

    @Override
    public URI uriDeAcesso(UUID id) {
        return URI.create("%s/%s/download".formatted(urlBase, id));
    }

    public Path getRaiz() {
        return raiz;
    }

    /** Resolve um caminho relativo garantindo que ele não escape da raiz (ex.: "../../etc/passwd"). */
    Path resolver(String caminhoRelativo) {
        Path caminho = raiz.resolve(caminhoRelativo).normalize();
        if (!caminho.startsWith(raiz) || caminho.equals(raiz)) {
            throw new IllegalArgumentException("Caminho fora do armazenamento: " + caminhoRelativo);
        }
        return caminho;
    }

    private static void mover(Path origem, Path destino) throws IOException {
        try {
            Files.move(origem, destino, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origem, destino);
        }
    }

    private static void removerSeHouverRollback(Path arquivo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        arquivo.toFile().setWritable(true);
                        Files.deleteIfExists(arquivo);
                        log.info("Rollback: comprovante {} removido do armazenamento", arquivo.getFileName());
                    } catch (IOException e) {
                        log.warn("Não foi possível remover {} após rollback: {}", arquivo, e.getMessage());
                    }
                }
            }
        });
    }
}
