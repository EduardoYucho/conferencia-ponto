package br.com.conferenciaponto.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendConfigTest {

    @TempDir
    Path dist;

    private final FrontendConfig.RotasDoFrontend resolver = new FrontendConfig.RotasDoFrontend();
    private Resource local;

    @BeforeEach
    void montarDist() throws IOException {
        Files.writeString(dist.resolve("index.html"), "<div id=\"app\"></div>");
        Files.createDirectories(dist.resolve("assets"));
        Files.writeString(dist.resolve("assets/index-abc123.js"), "console.log(1)");
        local = new FileSystemResource(dist.toString() + "/");
    }

    private String nome(String caminho) throws IOException {
        Resource r = resolver.getResource(caminho, local);
        return r == null ? null : r.getFilename();
    }

    @Test
    @DisplayName("Arquivos existentes são servidos como estão")
    void arquivos() throws IOException {
        assertThat(nome("assets/index-abc123.js")).isEqualTo("index-abc123.js");
        assertThat(nome("index.html")).isEqualTo("index.html");
    }

    @Test
    @DisplayName("Rotas do Vue (modo history) e a raiz devolvem o index.html")
    void rotasDoVue() throws IOException {
        assertThat(nome("")).isEqualTo("index.html");
        assertThat(nome("auditoria")).isEqualTo("index.html");
        assertThat(nome("conciliacao")).isEqualTo("index.html");
        assertThat(nome("ausencias/")).isEqualTo("index.html");
    }

    @Test
    @DisplayName("Arquivo inexistente e rotas da API não viram index.html (404)")
    void naoEncontrados() throws IOException {
        assertThat(nome("assets/nao-existe.js")).isNull();
        assertThat(nome("favicon.ico")).isNull();
        assertThat(nome("api")).isNull();
        assertThat(nome("api/v1/nao-existe")).isNull();
    }

    @Test
    @DisplayName("Sem front-end empacotado (desenvolvimento): nada é servido fora da API")
    void semFrontend() throws IOException {
        Files.delete(dist.resolve("index.html"));
        assertThat(nome("auditoria")).isNull();
        assertThat(nome("")).isNull();
    }
}
