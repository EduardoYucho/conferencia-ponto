package br.com.conferenciaponto.modulos.backup.infrastructure.coleta;

import br.com.conferenciaponto.modulos.backup.domain.Coleta;
import br.com.conferenciaponto.modulos.backup.domain.Registro;
import br.com.conferenciaponto.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Os dados de controle das coletas (o backup em si nunca passa por aqui):
 * <pre>
 * &lt;pasta-estado&gt;\coletas\&lt;id&gt;\coleta.json    a coleta
 * &lt;pasta-estado&gt;\coletas\&lt;id&gt;\blocos.jsonl   um registro por pedaço gravado por quem recebe (cursor, arquivos e
 *                                         SHA-256 do bloco): é o que permite retomar e montar o manifesto
 * </pre>
 */
@Component
public class RepositorioDeColetas {

    private static final Logger LOG = LoggerFactory.getLogger(RepositorioDeColetas.class);

    private final ConfiguracaoDoBackup configuracao;
    private final ObjectMapper json;

    public RepositorioDeColetas(ConfiguracaoDoBackup configuracao, ObjectMapper json) {
        this.configuracao = configuracao;
        this.json = json;
    }

    public Path pastaDaColeta(String id) {
        return configuracao.pastaInterna().resolve("coletas").resolve(id);
    }

    private Path registros(Coleta coleta) {
        return pastaDaColeta(coleta.id).resolve("blocos.jsonl");
    }

    public void salvar(Coleta coleta) {
        ArquivosJson.gravar(json, pastaDaColeta(coleta.id).resolve("coleta.json"), coleta);
    }

    /** Lê todas as coletas e refaz o andamento das que ainda estão em curso. */
    public List<Coleta> carregarTodas() {
        Path base = configuracao.pastaInterna().resolve("coletas");
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
                    if (coleta.situacao.ativa()) {
                        List<Registro> lidos = lerRegistros(coleta);
                        aplicarAndamento(coleta, lidos);
                        reescreverRegistros(coleta, lidos);
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

    /** Os registros em ordem; uma linha cortada no fim (de uma queda) é descartada. */
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
        Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Apaga os registros (depois de conferido, o manifesto já está dentro do backup, no computador de quem recebeu). */
    public void limparTrabalho(Coleta coleta) {
        try {
            Files.deleteIfExists(registros(coleta));
        } catch (IOException e) {
            LOG.warn("Não consegui limpar os registros da coleta {}: {}", coleta.id, e.getMessage());
        }
    }

    /** Tira a coleta do histórico (o backup, no computador de quem recebeu, não é tocado). */
    public void apagarColeta(Coleta coleta) throws IOException {
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
