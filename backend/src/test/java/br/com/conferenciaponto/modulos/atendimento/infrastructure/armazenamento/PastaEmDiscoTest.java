package br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeituraDoPdfException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A pasta dos atendimentos: gravação atômica, nomes saneados e nenhum caminho fora da raiz. */
class PastaEmDiscoTest {

    @TempDir
    Path raiz;

    private PastaEmDisco pasta() {
        return new PastaEmDisco(raiz);
    }

    private static InputStream bytes(int tamanho) {
        return new ByteArrayInputStream(new byte[tamanho]);
    }

    private long arquivosEmRecebendo() throws IOException {
        Path recebendo = raiz.resolve(".recebendo");
        if (!Files.exists(recebendo)) {
            return 0;
        }
        try (Stream<Path> arquivos = Files.list(recebendo)) {
            return arquivos.count();
        }
    }

    @Test
    void recebeNumTemporarioEGuardaComoConversaPdf() throws IOException {
        PastaEmDisco pasta = pasta();
        UUID id = UUID.randomUUID();

        Path recebido = pasta.receber(bytes(1000), 5000);
        assertThat(recebido).startsWithRaw(raiz.resolve(".recebendo")).hasSize(1000);
        assertThat(recebido.getFileName().toString()).endsWith(".parcial");

        pasta.guardarPdf(id, recebido);

        assertThat(raiz.resolve(id.toString()).resolve("conversa.pdf")).exists().hasSize(1000);
        assertThat(recebido).doesNotExist();
        assertThat(arquivosEmRecebendo()).isZero();
    }

    @Test
    void envioMaiorQueOLimiteEApagadoNaHora() throws IOException {
        PastaEmDisco pasta = pasta();

        assertThatThrownBy(() -> pasta.receber(bytes(5001), 5000))
                .isInstanceOf(LeituraDoPdfException.class)
                .hasFieldOrPropertyWithValue("codigo", "PDF_GRANDE_DEMAIS");
        assertThat(arquivosEmRecebendo()).isZero();
    }

    @Test
    void envioInterrompidoNaoDeixaArquivoPelaMetade() throws IOException {
        PastaEmDisco pasta = pasta();
        InputStream cai = new InputStream() {
            int lidos;

            @Override
            public int read() throws IOException {
                if (lidos++ > 100) {
                    throw new IOException("conexão caiu");
                }
                return 1;
            }
        };

        assertThatThrownBy(() -> pasta.receber(cai, 5000)).isInstanceOf(UncheckedIOException.class);
        assertThat(arquivosEmRecebendo()).isZero();
    }

    @Test
    void naSubidaApagaOsEnviosQueFicaramPelaMetade() throws IOException {
        Path recebendo = Files.createDirectories(raiz.resolve(".recebendo"));
        Files.writeString(recebendo.resolve("envio-123.parcial"), "metade");
        Files.writeString(recebendo.resolve("outro.txt"), "não é envio");

        pasta();

        assertThat(recebendo.resolve("envio-123.parcial")).doesNotExist();
        assertThat(recebendo.resolve("outro.txt")).exists();
    }

    @Test
    void descartarNaoFalhaSeOArquivoJaNaoExiste() {
        PastaEmDisco pasta = pasta();

        pasta.descartar(raiz.resolve(".recebendo").resolve("nao-existe.parcial"));
        pasta.descartar(null);
    }

    @Test
    void apagaAPastaInteiraDoAtendimento() throws IOException {
        PastaEmDisco pasta = pasta();
        UUID id = UUID.randomUUID();
        pasta.guardarPdf(id, pasta.receber(bytes(10), 100));
        Path anexo = pasta.caminhoDoArquivo(id, "anexo", 1, "foto.jpeg");
        Files.createDirectories(anexo.getParent());
        Files.writeString(anexo, "imagem");

        pasta.apagar(id);
        pasta.apagar(id); // de novo: não falha

        assertThat(raiz.resolve(id.toString())).doesNotExist();
    }

    @Test
    void nomeDeArquivoComPastasNaoEscapaDaPastaDoAtendimento() {
        PastaEmDisco pasta = pasta();
        UUID id = UUID.randomUUID();
        Path dele = raiz.resolve(id.toString()).resolve("arquivos");

        assertThat(pasta.caminhoDoArquivo(id, "anexo", 1, "..\\..\\..\\Windows\\System32\\x.dll")).startsWithRaw(dele).hasFileName("anexo_001_x.dll");
        assertThat(pasta.caminhoDoArquivo(id, "anexo", 2, "../../../etc/passwd")).startsWithRaw(dele).hasFileName("anexo_002_passwd");
        assertThat(pasta.caminhoDoArquivo(id, "../..", 3, "..")).startsWithRaw(dele).hasFileName("arquivo_003_arquivo");
        assertThat(pasta.caminhoDoArquivo(id, "ligacao", 4, "C:\\Users\\x\\a.txt")).startsWithRaw(dele).hasFileName("ligacao_004_a.txt");
    }

    @Test
    void caminhoForaDaRaizERecusado() {
        PastaEmDisco pasta = pasta();

        assertThatThrownBy(() -> pasta.dentroDaRaiz(raiz.resolve("..").resolve("fora.txt")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pasta.dentroDaRaiz(raiz)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pasta.dentroDaRaiz(raiz.resolve("a").resolve("..").resolve("b"))).isEqualTo(raiz.resolve("b"));
    }

    @Test
    void nomeSeguro() {
        assertThat(PastaEmDisco.nomeSeguro("Relatório de vendas.pdf")).isEqualTo("Relatorio_de_vendas.pdf");
        assertThat(PastaEmDisco.nomeSeguro(".oculto")).isEqualTo("oculto");
        assertThat(PastaEmDisco.nomeSeguro("a..b...c.txt")).isEqualTo("a.b.c.txt");
        assertThat(PastaEmDisco.nomeSeguro("CON:<>|?*.txt")).isEqualTo("CON_.txt");
        assertThat(PastaEmDisco.nomeSeguro("")).isEqualTo("arquivo");
        assertThat(PastaEmDisco.nomeSeguro(null)).isEqualTo("arquivo");
        assertThat(PastaEmDisco.nomeSeguro("fim com ponto.")).isEqualTo("fim_com_ponto");
        String longo = PastaEmDisco.nomeSeguro("x".repeat(300) + ".jpeg");
        assertThat(longo).hasSize(80).endsWith(".jpeg");
    }
}
