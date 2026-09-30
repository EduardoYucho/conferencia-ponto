package br.com.conferenciaponto.infrastructure.armazenamento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArmazenamentoLocalComprovantesTest {

    @TempDir
    Path raiz;

    @Test
    @DisplayName("Grava em <ano>/<mês>/comprovante_<uuid>.pdf, somente leitura, sem temporários sobrando")
    void armazena() throws Exception {
        ArmazenamentoLocalComprovantes armazenamento = new ArmazenamentoLocalComprovantes(raiz, "/api/comprovantes");
        UUID id = UUID.fromString("0b6a1f2e-3c4d-4e5f-8a9b-0c1d2e3f4a5b");
        byte[] pdf = "%PDF-1.4 teste".getBytes();

        String caminho = armazenamento.armazenar(id, pdf, LocalDate.of(2026, 9, 28));

        assertThat(caminho).isEqualTo("2026/09/comprovante_0b6a1f2e-3c4d-4e5f-8a9b-0c1d2e3f4a5b.pdf");
        Path arquivo = raiz.resolve(caminho);
        assertThat(arquivo).exists().hasBinaryContent(pdf);
        assertThat(Files.isWritable(arquivo)).isFalse();
        try (var conteudoPasta = Files.list(arquivo.getParent())) {
            assertThat(conteudoPasta).containsExactly(arquivo); // nenhum upload-*.tmp
        }
        assertThat(armazenamento.ler(caminho)).isEqualTo(pdf);
    }

    @Test
    @DisplayName("Gera a URI de download do comprovante")
    void uri() {
        ArmazenamentoLocalComprovantes armazenamento = new ArmazenamentoLocalComprovantes(raiz, "/api/comprovantes/");
        UUID id = UUID.randomUUID();
        assertThat(armazenamento.uriDeAcesso(id)).hasToString("/api/comprovantes/" + id + "/download");
    }

    @Test
    @DisplayName("Bloqueia caminhos fora da raiz (path traversal)")
    void pathTraversal() {
        ArmazenamentoLocalComprovantes armazenamento = new ArmazenamentoLocalComprovantes(raiz, "/api/comprovantes");
        assertThatThrownBy(() -> armazenamento.ler("../../segredo.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> armazenamento.ler("..")).isInstanceOf(IllegalArgumentException.class);
    }
}
