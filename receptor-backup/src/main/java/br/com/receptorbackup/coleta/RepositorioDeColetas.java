package br.com.receptorbackup.coleta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.receptorbackup.config.Propriedades;

/**
 * Onde cada coisa fica no disco:
 * <pre>
 * &lt;pasta dos backups&gt;\
 *   &lt;Cliente&gt;\&lt;Cliente&gt;_&lt;data&gt;.tar.zst          o backup (.parcial enquanto chega)
 *   &lt;Cliente&gt;\&lt;Cliente&gt;_&lt;data&gt;.json             resumo, conferência, alterados e não lidos
 *   .receptor\coletas\&lt;id&gt;\coleta.json             a coleta
 *   .receptor\coletas\&lt;id&gt;\blocos.jsonl            um registro por pedaço já no arquivo (para retomar)
 *   .receptor\coletas\&lt;id&gt;\pendentes\&lt;seq&gt;.parte    pedaço que chegou fora de ordem
 * </pre>
 */
@Component
public class RepositorioDeColetas {

    private static final Logger LOG = LoggerFactory.getLogger(RepositorioDeColetas.class);

    private final Propriedades propriedades;
    private final ObjectMapper json;

    public RepositorioDeColetas(Propriedades propriedades, ObjectMapper json) {
        this.propriedades = propriedades;
        this.json = json;
    }

    public Path pastaDaColeta(String id) {
        return propriedades.pastaInterna().resolve("coletas").resolve(id);
    }

    public Path pendentes(Coleta coleta) {
        return pastaDaColeta(coleta.id).resolve("pendentes");
    }

    public Path pastaDoCliente(Coleta coleta) {
        return propriedades.pastaDados().resolve(coleta.pastaCliente);
    }

    public Path arquivoFinal(Coleta coleta) {
        return pastaDoCliente(coleta).resolve(coleta.nome + ".tar.zst");
    }

    public Path arquivoParcial(Coleta coleta) {
        return pastaDoCliente(coleta).resolve(coleta.nome + ".tar.zst.parcial");
    }

    public Path arquivoResumo(Coleta coleta) {
        return pastaDoCliente(coleta).resolve(coleta.nome + ".json");
    }

    private Path registros(Coleta coleta) {
        return pastaDaColeta(coleta.id).resolve("blocos.jsonl");
    }

    public void salvar(Coleta coleta) {
        ArquivosJson.gravar(json, pastaDaColeta(coleta.id).resolve("coleta.json"), coleta);
    }

    /** Lê todas as coletas e refaz o andamento de cada uma a partir dos registros (e acerta o .parcial). */
    public List<Coleta> carregarTodas() {
        Path base = propriedades.pastaInterna().resolve("coletas");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<Coleta> coletas = new ArrayList<>();
        try (Stream<Path> pastas = Files.list(base)) {
            for (Path pasta : pastas.filter(Files::isDirectory).toList()) {
                Path arquivo = pasta.resolve("coleta.json");
                if (!Files.exists(arquivo)) {
                    continue;
                }
                try {
                    Coleta coleta = json.readValue(arquivo.toFile(), Coleta.class);
                    if (coleta.situacao.recebendo() || coleta.situacao == Coleta.Situacao.MONTANDO) {
                        refazerAndamento(coleta);
                    }
                    coletas.add(coleta);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Coleta em {} ignorada: {}", pasta, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        coletas.sort(Comparator.comparing((Coleta c) -> c.criadaEm).reversed());
        return coletas;
    }

    /**
     * Refaz próximo pedaço, tamanho e cursor a partir de blocos.jsonl (uma linha cortada no fim, de uma queda, é
     * descartada) e corta o .parcial no tamanho registrado: o que foi escrito depois do último registro não conta.
     */
    void refazerAndamento(Coleta coleta) throws IOException {
        List<Registro> lidos = lerRegistros(coleta);
        aplicarAndamento(coleta, lidos);
        reescreverRegistros(coleta, lidos);
        Path parcial = arquivoParcial(coleta);
        if (Files.exists(parcial)) {
            try (FileChannel canal = FileChannel.open(parcial, StandardOpenOption.WRITE)) {
                if (canal.size() > coleta.bytesArquivo) {
                    canal.truncate(coleta.bytesArquivo);
                }
            }
        }
    }

    public List<Registro> lerRegistros(Coleta coleta) throws IOException {
        Path arquivo = registros(coleta);
        List<Registro> lidos = new ArrayList<>();
        if (!Files.exists(arquivo)) {
            return lidos;
        }
        try (BufferedReader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            String linha;
            while ((linha = leitor.readLine()) != null) {
                if (linha.isBlank()) {
                    continue;
                }
                try {
                    Registro registro = json.readValue(linha, Registro.class);
                    if (registro.seq() != lidos.size()) {
                        break;
                    }
                    lidos.add(registro);
                } catch (IOException cortada) {
                    break;
                }
            }
        }
        return lidos;
    }

    static void aplicarAndamento(Coleta coleta, List<Registro> registros) {
        coleta.proximoSeq = registros.size();
        coleta.bytesArquivo = 0;
        coleta.cursor = null;
        coleta.brutoRecebido = 0;
        coleta.arquivosRecebidos = 0;
        for (Registro registro : registros) {
            coleta.bytesArquivo = registro.offsetFinal();
            coleta.cursor = registro.fim();
            coleta.brutoRecebido += registro.bruto();
            coleta.arquivosRecebidos += registro.arquivos() == null ? 0 : registro.arquivos().size();
        }
    }

    public void anexarRegistro(Coleta coleta, Registro registro) {
        try {
            Path arquivo = registros(coleta);
            Files.createDirectories(arquivo.getParent());
            byte[] linha = (json.writeValueAsString(registro) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                canal.write(ByteBuffer.wrap(linha));
                canal.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void reescreverRegistros(Coleta coleta, List<Registro> registros) throws IOException {
        Path arquivo = registros(coleta);
        Files.createDirectories(arquivo.getParent());
        Path temporario = arquivo.resolveSibling("blocos.jsonl.tmp");
        StringBuilder conteudo = new StringBuilder();
        for (Registro registro : registros) {
            conteudo.append(json.writeValueAsString(registro)).append('\n');
        }
        Files.writeString(temporario, conteudo, StandardCharsets.UTF_8);
        Files.move(temporario, arquivo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Apaga os registros e pedaços pendentes (depois que o arquivo foi fechado, o manifesto já está dentro dele). */
    public void limparTrabalho(Coleta coleta) {
        try {
            Files.deleteIfExists(registros(coleta));
            apagarArvore(pendentes(coleta));
        } catch (IOException e) {
            LOG.warn("Não consegui limpar o trabalho da coleta {}: {}", coleta.id, e.getMessage());
        }
    }

    public void apagarColeta(Coleta coleta) throws IOException {
        Files.deleteIfExists(arquivoFinal(coleta));
        Files.deleteIfExists(arquivoParcial(coleta));
        Files.deleteIfExists(arquivoResumo(coleta));
        apagarArvore(pastaDaColeta(coleta.id));
    }

    public static void apagarArvore(Path pasta) throws IOException {
        if (!Files.exists(pasta)) {
            return;
        }
        try (Stream<Path> todos = Files.walk(pasta)) {
            for (Path caminho : todos.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(caminho);
            }
        }
    }
}
