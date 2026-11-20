package br.com.receptorbackup.coleta;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;

/**
 * Lê um backup .tar.zst inteiro, de passagem (nada é gravado descompactado), para conferir ou extrair.
 * O manifesto ({@value #MANIFESTO}) é a última entrada: traz o SHA-256 que o navegador calculou de cada arquivo.
 */
public final class LeitorDoBackup {

    public static final String MANIFESTO = "_coleta/manifesto.json";
    private static final int LIMITE_LISTA = 200;

    public record ResultadoConferencia(boolean integra, long arquivosConferidos, long bytesConferidos,
            List<String> divergentes, List<String> faltando, List<String> sobrando, String mensagem) {
    }

    public record ResultadoExtracao(long arquivos, long bytes, List<String> ignorados) {
    }

    private LeitorDoBackup() {
    }

    private static TarArchiveInputStream abrir(Path arquivo) throws IOException {
        InputStream bruto = new BufferedInputStream(Files.newInputStream(arquivo), 1 << 20);
        ZstdInputStream zstd = new ZstdInputStream(bruto);
        return new TarArchiveInputStream(new BufferedInputStream(zstd, 1 << 20), StandardCharsets.UTF_8.name());
    }

    /** Recalcula o SHA-256 de cada arquivo e compara com o manifesto. {@code progresso} recebe os bytes lidos. */
    public static ResultadoConferencia conferir(Path arquivo, ObjectMapper json, LongConsumer progresso)
            throws IOException {
        Map<String, String> shaCalculado = new HashMap<>();
        Map<String, Long> tamanhoLido = new HashMap<>();
        JsonNode manifesto = null;
        long bytes = 0;
        byte[] buffer = new byte[1 << 20];
        try (TarArchiveInputStream tar = abrir(arquivo)) {
            TarArchiveEntry entrada;
            while ((entrada = tar.getNextEntry()) != null) {
                String nome = entrada.getName();
                if (MANIFESTO.equals(nome)) {
                    manifesto = json.readTree(tar.readAllBytes());
                    continue;
                }
                if (!entrada.isFile()) {
                    continue;
                }
                MessageDigest sha = novoSha();
                long tamanho = 0;
                int lidos;
                while ((lidos = tar.read(buffer)) != -1) {
                    sha.update(buffer, 0, lidos);
                    tamanho += lidos;
                    bytes += lidos;
                    progresso.accept(bytes);
                }
                shaCalculado.put(nome, HexFormat.of().formatHex(sha.digest()));
                tamanhoLido.put(nome, tamanho);
            }
        }
        if (manifesto == null) {
            return new ResultadoConferencia(false, 0, bytes, List.of(), List.of(), List.of(),
                    "O backup não tem o manifesto no fim: o arquivo está incompleto.");
        }
        List<String> divergentes = new ArrayList<>();
        List<String> faltando = new ArrayList<>();
        long conferidos = 0;
        long nDivergentes = 0;
        long nFaltando = 0;
        for (JsonNode item : manifesto.path("arquivos")) {
            if (!"arquivo".equals(item.path("tipo").asText("arquivo"))) {
                continue;
            }
            String caminho = item.path("caminho").asText();
            String calculado = shaCalculado.remove(caminho);
            if (calculado == null) {
                nFaltando++;
                adicionar(faltando, caminho);
            } else if (!calculado.equalsIgnoreCase(item.path("sha256").asText())
                    || tamanhoLido.get(caminho) != item.path("tamanho").asLong(-1)) {
                nDivergentes++;
                adicionar(divergentes, caminho);
            } else {
                conferidos++;
            }
        }
        List<String> sobrando = new ArrayList<>();
        long nSobrando = shaCalculado.size();
        shaCalculado.keySet().stream().sorted().forEach(c -> adicionar(sobrando, c));
        boolean integra = nDivergentes == 0 && nFaltando == 0 && nSobrando == 0;
        String mensagem = integra ? "Todos os " + conferidos + " arquivos idênticos aos lidos no cliente."
                : "Diferenças: " + nDivergentes + " divergente(s), " + nFaltando + " faltando, " + nSobrando
                        + " a mais.";
        return new ResultadoConferencia(integra, conferidos, bytes, divergentes, faltando, sobrando, mensagem);
    }

    /**
     * Extrai em {@code destino} (que precisa estar vazio ou não existir). Entradas com caminho perigoso são puladas e
     * listadas em {@code ignorados}.
     */
    public static ResultadoExtracao extrair(Path arquivo, Path destino, LongConsumer progresso) throws IOException {
        Files.createDirectories(destino);
        try (var conteudo = Files.list(destino)) {
            if (conteudo.findAny().isPresent()) {
                throw new ErroDeColeta(409, "DESTINO_OCUPADO", "A pasta de destino precisa estar vazia: " + destino);
            }
        }
        List<String> ignorados = new ArrayList<>();
        long arquivos = 0;
        long bytes = 0;
        byte[] buffer = new byte[1 << 20];
        try (TarArchiveInputStream tar = abrir(arquivo)) {
            TarArchiveEntry entrada;
            while ((entrada = tar.getNextEntry()) != null) {
                var alvo = NomesSeguros.dentroDe(destino, entrada.getName());
                if (alvo.isEmpty()) {
                    adicionar(ignorados, entrada.getName());
                    continue;
                }
                Path caminho = alvo.get();
                if (entrada.isDirectory()) {
                    Files.createDirectories(caminho);
                    continue;
                }
                if (!entrada.isFile()) {
                    adicionar(ignorados, entrada.getName());
                    continue;
                }
                Files.createDirectories(caminho.getParent());
                try (OutputStream saida = Files.newOutputStream(caminho)) {
                    int lidos;
                    while ((lidos = tar.read(buffer)) != -1) {
                        saida.write(buffer, 0, lidos);
                        bytes += lidos;
                        progresso.accept(bytes);
                    }
                }
                if (entrada.getModTime() != null) {
                    Files.setLastModifiedTime(caminho, FileTime.fromMillis(entrada.getModTime().getTime()));
                }
                arquivos++;
            }
        }
        return new ResultadoExtracao(arquivos, bytes, ignorados);
    }

    /** SHA-256 de um arquivo inteiro (usado nos testes). */
    public static String sha256(Path arquivo) throws IOException {
        MessageDigest sha = novoSha();
        try (InputStream entrada = new DigestInputStream(Files.newInputStream(arquivo), sha)) {
            entrada.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest novoSha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void adicionar(List<String> lista, String item) {
        if (lista.size() < LIMITE_LISTA) {
            lista.add(item);
        }
    }
}
