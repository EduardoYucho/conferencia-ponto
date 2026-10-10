package br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeituraDoPdfException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A pasta do módulo ({@code atendimento.armazenamento.diretorio}):
 *
 * <pre>
 * atendimentos/
 * ├── .recebendo/           envios em andamento (arquivos temporários)
 * └── &lt;id do atendimento&gt;/
 *     ├── conversa.pdf
 *     └── arquivos/         001_&lt;nome saneado&gt;.jpeg ... (a partir da próxima etapa)
 * </pre>
 *
 * Os arquivos são gravados num temporário da própria pasta e movidos no fim (nunca fica um arquivo pela metade com o
 * nome final). Nomes vindos de fora são saneados, e todo caminho é conferido para ficar dentro da pasta.
 */
@Component
public class PastaEmDisco implements PastaDosAtendimentos {

    private static final Logger log = LoggerFactory.getLogger(PastaEmDisco.class);
    private static final String RECEBENDO = ".recebendo";
    private static final String PDF_DA_CONVERSA = "conversa.pdf";
    private static final int TAMANHO_DO_NOME = 80;

    private final Path raiz;

    public PastaEmDisco(@Value("${atendimento.armazenamento.diretorio}") Path raiz) {
        this.raiz = raiz.toAbsolutePath().normalize();
        limparEnviosInterrompidos();
    }

    /** Envios que ficaram pela metade porque o sistema parou no meio: na subida não há envio em andamento. */
    private void limparEnviosInterrompidos() {
        Path pasta = raiz.resolve(RECEBENDO);
        if (!Files.isDirectory(pasta)) {
            return;
        }
        try (Stream<Path> arquivos = Files.list(pasta)) {
            arquivos.filter(a -> a.getFileName().toString().endsWith(".parcial")).forEach(this::descartar);
        } catch (IOException e) {
            log.warn("Não foi possível limpar os envios interrompidos: {}", e.toString());
        }
    }

    @Override
    public Path receber(InputStream conteudo, long limite) {
        Path temporario;
        try {
            temporario = Files.createTempFile(criar(raiz.resolve(RECEBENDO)), "envio-", ".parcial");
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível criar o arquivo temporário do envio", e);
        }
        try (OutputStream saida = Files.newOutputStream(temporario)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int lidos;
            while ((lidos = conteudo.read(buffer)) != -1) {
                total += lidos;
                if (total > limite) {
                    throw LeituraDoPdfException.grandeDemais(DataSize.ofBytes(limite).toMegabytes() + " MB");
                }
                saida.write(buffer, 0, lidos);
            }
        } catch (IOException e) {
            descartar(temporario);
            throw new UncheckedIOException("O envio do arquivo foi interrompido ou não pôde ser gravado", e);
        } catch (RuntimeException e) {
            descartar(temporario);
            throw e;
        }
        return temporario;
    }

    @Override
    public void guardarPdf(UUID atendimentoId, Path recebido) {
        Path destino = dentroDaRaiz(pastaDo(atendimentoId).resolve(PDF_DA_CONVERSA));
        try {
            criar(destino.getParent());
            try {
                Files.move(recebido, destino, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(recebido, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível guardar o PDF do atendimento " + atendimentoId, e);
        }
    }

    @Override
    public void descartar(Path recebido) {
        if (recebido == null) {
            return;
        }
        try {
            Files.deleteIfExists(recebido);
        } catch (IOException e) {
            log.warn("Não foi possível apagar o arquivo temporário {}: {}", recebido.getFileName(), e.toString());
        }
    }

    @Override
    public void apagar(UUID atendimentoId) {
        Path pasta = pastaDo(atendimentoId);
        if (!Files.exists(pasta)) {
            return;
        }
        try (Stream<Path> caminhos = Files.walk(pasta)) {
            for (Path caminho : caminhos.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(caminho);
                } catch (NoSuchFileException e) {
                    // já foi
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível apagar os arquivos do atendimento " + atendimentoId, e);
        }
    }

    /** Onde fica (ou ficará) um anexo: {@code <id>/arquivos/<ordem>_<nome saneado>}, sempre dentro da pasta. */
    public Path caminhoDoAnexo(UUID atendimentoId, int ordem, String nomeOriginal) {
        return dentroDaRaiz(pastaDo(atendimentoId).resolve("arquivos").resolve("%03d_%s".formatted(ordem, nomeSeguro(nomeOriginal))));
    }

    Path pastaDo(UUID atendimentoId) {
        return dentroDaRaiz(raiz.resolve(atendimentoId.toString()));
    }

    /** Recusa qualquer caminho que, depois de resolvido, saia da pasta do módulo. */
    Path dentroDaRaiz(Path caminho) {
        Path normalizado = caminho.toAbsolutePath().normalize();
        if (!normalizado.startsWith(raiz) || normalizado.equals(raiz)) {
            throw new IllegalArgumentException("Caminho fora da pasta dos atendimentos");
        }
        return normalizado;
    }

    /**
     * Nome que pode ir para o disco: só a última parte (sem pastas), sem acentos, só letras, números, ".", "-" e
     * "_", sem ponto no começo e com até {@value #TAMANHO_DO_NOME} caracteres (a extensão é mantida).
     */
    static String nomeSeguro(String nome) {
        String base = nome == null ? "" : nome;
        base = base.substring(Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\')) + 1);
        base = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        base = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_{2,}", "_").replaceAll("\\.{2,}", ".");
        base = base.replaceAll("^[._-]+", "").replaceAll("[.\\s]+$", "");
        if (base.length() > TAMANHO_DO_NOME) {
            int ponto = base.lastIndexOf('.');
            String extensao = ponto > 0 && base.length() - ponto <= 11 ? base.substring(ponto) : "";
            base = base.substring(0, TAMANHO_DO_NOME - extensao.length()) + extensao;
        }
        return base.isEmpty() ? "arquivo" : base;
    }

    private static Path criar(Path pasta) {
        try {
            return Files.createDirectories(pasta);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível criar a pasta " + pasta.getFileName(), e);
        }
    }
}
