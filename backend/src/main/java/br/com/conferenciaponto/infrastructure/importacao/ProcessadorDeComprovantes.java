package br.com.conferenciaponto.infrastructure.importacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Contrato usado pelo monitor de diretório (facilita testar o watcher isoladamente). */
public interface ProcessadorDeComprovantes {

    Logger LOG = LoggerFactory.getLogger(ProcessadorDeComprovantes.class);

    /**
     * Processa um PDF. Não deve lançar exceção: falhas são registradas em log/auditoria.
     *
     * @return {@code true} se o arquivo foi tratado (importado, duplicado, inválido...);
     *         {@code false} se não foi possível lê-lo agora e vale tentar de novo depois
     *         (arquivo em uso, falha de rede, banco fora do ar)
     */
    boolean processar(Path arquivo);

    /** Processa todos os PDFs da pasta, do mais antigo para o mais recente. */
    default void processarDiretorio(Path diretorio) {
        try {
            listarPdfs(diretorio).forEach(pdf -> processar(pdf.caminho()));
        } catch (IOException e) {
            LOG.warn("Não foi possível listar {}: {}", diretorio, e.getMessage());
        }
    }

    /** PDF encontrado na pasta, com os atributos lidos na própria listagem. */
    record ArquivoPdf(Path caminho, long tamanho, long modificadoEm) {
    }

    /**
     * Lista os PDFs da pasta (sem subpastas), do mais antigo para o mais recente.
     *
     * <p>Usa {@link Files#walkFileTree}, que no Windows aproveita os atributos devolvidos pela
     * própria enumeração da pasta: numa pasta de rede isso é uma ida ao servidor para a pasta
     * inteira, e não duas por arquivo.
     *
     * @throws IOException se a pasta não puder ser lida (inexistente, rede fora, sem permissão)
     */
    static List<ArquivoPdf> listarPdfs(Path diretorio) throws IOException {
        List<ArquivoPdf> pdfs = new ArrayList<>();
        Files.walkFileTree(diretorio, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path arquivo, BasicFileAttributes atributos) {
                if (atributos.isRegularFile() && ehPdf(arquivo)) {
                    pdfs.add(new ArquivoPdf(arquivo, atributos.size(), atributos.lastModifiedTime().toMillis()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path arquivo, IOException erro) throws IOException {
                if (arquivo.equals(diretorio)) {
                    throw erro; // a própria pasta está inacessível
                }
                return FileVisitResult.CONTINUE; // um arquivo com problema não impede os demais
            }
        });
        pdfs.sort(Comparator.comparingLong(ArquivoPdf::modificadoEm));
        return pdfs;
    }

    static boolean ehPdf(Path arquivo) {
        Path nome = arquivo.getFileName();
        return nome != null && nome.toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
}
