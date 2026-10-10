package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Nome, extensão e parâmetros dos links de anexo do Digisac. */
class AnexosDoLinkTest {

    private static final String URL = "https://bucket.exemplo.com/pasta/arquivo.bin";

    @Test
    void nomeDoContentDisposition() {
        assertThat(LeitorConversaDigisac.nomeDoAnexo("attachment; filename*=UTF-8''Relat%C3%B3rio%20final.pdf", URL, 1))
                .isEqualTo("Relatório final.pdf");
        assertThat(LeitorConversaDigisac.nomeDoAnexo("attachment; filename=\"foto 1.png\"", URL, 1)).isEqualTo("foto 1.png");
        assertThat(LeitorConversaDigisac.nomeDoAnexo("attachment; filename=1760011620000.jpeg", URL, 1))
                .isEqualTo("1760011620000.jpeg");
    }

    @Test
    void semNomeNoLinkUsaOCaminhoOuUmNomeGenerico() {
        assertThat(LeitorConversaDigisac.nomeDoAnexo(null, "https://b.exemplo.com/x/video.mp4?X-Amz-Signature=1", 2))
                .isEqualTo("video.mp4");
        assertThat(LeitorConversaDigisac.nomeDoAnexo(null, "https://b.exemplo.com/x/?X-Amz-Signature=1", 3)).isEqualTo("anexo-3");
        assertThat(LeitorConversaDigisac.nomeDoAnexo("attachment", "https://b.exemplo.com/", 4)).isEqualTo("anexo-4");
    }

    @Test
    void oNomeNuncaTrazPastas() {
        assertThat(LeitorConversaDigisac.nomeDoAnexo("attachment; filename=\"..\\..\\Windows\\virus.exe\"", URL, 1))
                .isEqualTo("virus.exe");
        assertThat(LeitorConversaDigisac.nomeDoAnexo("attachment; filename=../../etc/passwd", URL, 1)).isEqualTo("passwd");
    }

    @Test
    void extensao() {
        assertThat(LeitorConversaDigisac.extensao("audio.OGA")).isEqualTo("oga");
        assertThat(LeitorConversaDigisac.extensao("sem-extensao")).isEmpty();
        assertThat(LeitorConversaDigisac.extensao("arquivo.")).isEmpty();
        assertThat(LeitorConversaDigisac.extensao(".oculto")).isEmpty();
        assertThat(LeitorConversaDigisac.extensao("nome.com espaço")).isEmpty();
    }

    @Test
    void parametrosDaUrlComNomeEmMinusculas() {
        assertThat(LeitorConversaDigisac.parametros("https://b/x?X-Amz-Date=20261009T120000Z&X-Amz-Expires=86400&vazio"))
                .containsEntry("x-amz-date", "20261009T120000Z")
                .containsEntry("x-amz-expires", "86400")
                .containsEntry("vazio", "");
        assertThat(LeitorConversaDigisac.parametros("https://b/x")).isEmpty();
    }

    @Test
    void categoriaPelaExtensao() {
        assertThat(CategoriaDoArquivo.porExtensao("JPEG")).isEqualTo(CategoriaDoArquivo.IMAGEM);
        assertThat(CategoriaDoArquivo.porExtensao("oga")).isEqualTo(CategoriaDoArquivo.AUDIO);
        assertThat(CategoriaDoArquivo.porExtensao("mov")).isEqualTo(CategoriaDoArquivo.VIDEO);
        assertThat(CategoriaDoArquivo.porExtensao("pdf")).isEqualTo(CategoriaDoArquivo.DOCUMENTO);
        assertThat(CategoriaDoArquivo.porExtensao("docx")).isEqualTo(CategoriaDoArquivo.OUTRO);
        assertThat(CategoriaDoArquivo.porExtensao("")).isEqualTo(CategoriaDoArquivo.OUTRO);
        assertThat(CategoriaDoArquivo.tipoDeMidia("oga")).isEqualTo("audio/ogg");
        assertThat(CategoriaDoArquivo.tipoDeMidia("docx")).isNull();
    }
}
