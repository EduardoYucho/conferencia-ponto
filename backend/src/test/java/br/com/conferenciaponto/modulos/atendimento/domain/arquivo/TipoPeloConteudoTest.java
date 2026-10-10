package br.com.conferenciaponto.modulos.atendimento.domain.arquivo;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** O tipo pelos primeiros bytes (a extensão do nome pode mentir). */
class TipoPeloConteudoTest {

    private static TipoDetectado tipo(String extensao, int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return TipoPeloConteudo.detectar(b, b.length, extensao);
    }

    private static TipoDetectado tipo(String extensao, String inicio) {
        byte[] b = inicio.getBytes(StandardCharsets.ISO_8859_1);
        return TipoPeloConteudo.detectar(Arrays.copyOf(b, Math.max(b.length, 16)), Math.max(b.length, 16), extensao);
    }

    private static String ftyp(String marca) {
        return "\0\0\0\u0018ftyp" + marca + "\0\0\0\0";
    }

    @Test
    void imagens() {
        assertThat(tipo("jpeg", 0xFF, 0xD8, 0xFF, 0xE0)).extracting(TipoDetectado::tipoDeMidia, TipoDetectado::suportado)
                .containsExactly("image/jpeg", true);
        assertThat(tipo("png", 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A).tipoDeMidia()).isEqualTo("image/png");
        assertThat(tipo("webp", "RIFF\0\0\0\0WEBPVP8 ").tipoDeMidia()).isEqualTo("image/webp");
        assertThat(tipo("heic", ftyp("heic")).tipoDeMidia()).isEqualTo("image/heic");
        assertThat(tipo("jpg", "GIF89a").suportado()).isFalse();
    }

    @Test
    void audios() {
        assertThat(tipo("oga", "OggS\0\2").tipoDeMidia()).isEqualTo("audio/ogg");
        assertThat(tipo("mp3", "ID3\4\0").tipoDeMidia()).isEqualTo("audio/mpeg");
        assertThat(tipo("mp3", 0xFF, 0xFB, 0x90, 0x00).tipoDeMidia()).isEqualTo("audio/mpeg");
        assertThat(tipo("aac", 0xFF, 0xF1, 0x50, 0x80).tipoDeMidia()).isEqualTo("audio/aac");
        assertThat(tipo("wav", "RIFF\0\0\0\0WAVEfmt ").tipoDeMidia()).isEqualTo("audio/wav");
        assertThat(tipo("flac", "fLaC\0\0").tipoDeMidia()).isEqualTo("audio/flac");
        assertThat(tipo("m4a", ftyp("M4A "))).extracting(TipoDetectado::categoria, TipoDetectado::tipoDeMidia)
                .containsExactly(CategoriaDoArquivo.AUDIO, "audio/mp4");
        assertThat(tipo("m4a", ftyp("isom")).categoria()).as("m4a com marca genérica").isEqualTo(CategoriaDoArquivo.AUDIO);
        assertThat(tipo("amr", "#!AMR\n").suportado()).isFalse();
    }

    @Test
    void videos() {
        assertThat(tipo("mp4", ftyp("isom"))).extracting(TipoDetectado::categoria, TipoDetectado::tipoDeMidia)
                .containsExactly(CategoriaDoArquivo.VIDEO, "video/mp4");
        assertThat(tipo("mov", ftyp("qt  ")).tipoDeMidia()).isEqualTo("video/quicktime");
        assertThat(tipo("webm", 0x1A, 0x45, 0xDF, 0xA3, 0x9F, 'B', 0x82, 0x84, 'w', 'e', 'b', 'm').tipoDeMidia())
                .isEqualTo("video/webm");
        assertThat(tipo("mkv", 0x1A, 0x45, 0xDF, 0xA3, 0x9F, 'B', 0x82, 0x88, 'm', 'a', 't', 'r').tipoDeMidia())
                .isEqualTo("video/x-matroska");
        assertThat(tipo("avi", "RIFF\0\0\0\0AVI LIST").tipoDeMidia()).isEqualTo("video/x-msvideo");
    }

    @Test
    void documentos() {
        assertThat(tipo("pdf", "%PDF-1.7\n")).extracting(TipoDetectado::categoria, TipoDetectado::suportado)
                .containsExactly(CategoriaDoArquivo.DOCUMENTO, true);
        assertThat(tipo("txt", "Erro na tela de vendas às 10h").tipoDeMidia()).isEqualTo("text/plain");
        assertThat(tipo("bin", "Erro na tela de vendas").suportado()).as("texto só com extensão de texto").isFalse();
    }

    @Test
    void wordExcelECompactadosNaoSaoSuportados() {
        assertThat(tipo("docx", 'P', 'K', 0x03, 0x04)).extracting(TipoDetectado::suportado, TipoDetectado::descricao)
                .containsExactly(false, "documento do Word");
        assertThat(tipo("xlsx", 'P', 'K', 0x03, 0x04).descricao()).isEqualTo("planilha do Excel");
        assertThat(tipo("zip", 'P', 'K', 0x03, 0x04).descricao()).isEqualTo("arquivo compactado");
        assertThat(tipo("doc", 0xD0, 0xCF, 0x11, 0xE0).suportado()).isFalse();
    }

    @Test
    void aExtensaoNaoEngana() {
        assertThat(tipo("png", 'P', 'K', 0x03, 0x04).suportado()).isFalse();
        assertThat(tipo("pdf", 0xFF, 0xD8, 0xFF, 0xE0).tipoDeMidia()).isEqualTo("image/jpeg");
        assertThat(TipoPeloConteudo.detectar(new byte[0], 0, "png").suportado()).isFalse();
    }
}
