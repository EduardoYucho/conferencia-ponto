package br.com.conferenciaponto.modulos.atendimento.domain.arquivo;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Confere o tipo do arquivo pelos primeiros bytes. Os tipos aceitos são os que o Gemini lê:
 * <ul>
 *   <li>imagem: png, jpeg, webp, heic/heif;</li>
 *   <li>áudio: mp3, m4a, wav, ogg/oga/opus, aac, flac;</li>
 *   <li>vídeo: mp4, mov, webm, avi, mkv;</li>
 *   <li>documento: pdf e texto.</li>
 * </ul>
 * Word, Excel, compactados e os demais ficam como não suportados ("exporte em PDF e envie à mão").
 */
public final class TipoPeloConteudo {

    private static final int AMOSTRA = 4096;
    private static final Set<String> EXTENSOES_DE_TEXTO = Set.of("txt", "csv", "log", "xml", "json");
    private static final Set<String> MARCAS_HEIC = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis");
    private static final Set<String> MARCAS_HEIF = Set.of("mif1", "msf1", "heif");
    private static final Set<String> MARCAS_M4A = Set.of("M4A ", "M4B ", "M4P ", "F4A ");
    private static final Set<String> MARCAS_3GP = Set.of("3gp4", "3gp5", "3gp6", "3gp7", "3g2a", "3g2b", "3ge6", "3gg6");
    private static final Set<String> EXTENSOES_DE_AUDIO_MP4 = Set.of("m4a", "m4b", "aac");

    private TipoPeloConteudo() {
    }

    public static TipoDetectado detectar(Path arquivo, String nomeOriginal) {
        byte[] amostra = new byte[AMOSTRA];
        int lidos;
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            lidos = entrada.readNBytes(amostra, 0, AMOSTRA);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o começo do arquivo", e);
        }
        return detectar(amostra, lidos, extensao(nomeOriginal));
    }

    static TipoDetectado detectar(byte[] b, int n, String extensao) {
        if (n == 0) {
            return naoSuportado(CategoriaDoArquivo.OUTRO, null, "arquivo vazio");
        }
        if (comeca(b, n, 0xFF, 0xD8, 0xFF)) {
            return ok(CategoriaDoArquivo.IMAGEM, "image/jpeg");
        }
        if (comeca(b, n, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return ok(CategoriaDoArquivo.IMAGEM, "image/png");
        }
        if (texto(b, n, 0, "RIFF") && n >= 12) {
            String tipo = ascii(b, 8, 4);
            if (tipo.equals("WEBP")) {
                return ok(CategoriaDoArquivo.IMAGEM, "image/webp");
            }
            if (tipo.equals("WAVE")) {
                return ok(CategoriaDoArquivo.AUDIO, "audio/wav");
            }
            if (tipo.equals("AVI ")) {
                return ok(CategoriaDoArquivo.VIDEO, "video/x-msvideo");
            }
        }
        if (n >= 12 && texto(b, n, 4, "ftyp")) {
            return isoBmff(ascii(b, 8, 4), extensao);
        }
        if (texto(b, n, 0, "%PDF-")) {
            return ok(CategoriaDoArquivo.DOCUMENTO, "application/pdf");
        }
        if (texto(b, n, 0, "OggS")) {
            return ok(CategoriaDoArquivo.AUDIO, "audio/ogg");
        }
        if (texto(b, n, 0, "fLaC")) {
            return ok(CategoriaDoArquivo.AUDIO, "audio/flac");
        }
        if (texto(b, n, 0, "ID3")) {
            return ok(CategoriaDoArquivo.AUDIO, "audio/mpeg");
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xF6) == 0xF0) {
            return ok(CategoriaDoArquivo.AUDIO, "audio/aac");
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) {
            return ok(CategoriaDoArquivo.AUDIO, "audio/mpeg");
        }
        if (comeca(b, n, 0x1A, 0x45, 0xDF, 0xA3)) {
            boolean webm = ascii(b, 0, Math.min(n, 64)).contains("webm");
            if (webm && "weba".equals(extensao)) {
                return ok(CategoriaDoArquivo.AUDIO, "audio/webm");
            }
            return ok(CategoriaDoArquivo.VIDEO, webm ? "video/webm" : "video/x-matroska");
        }
        if (texto(b, n, 0, "GIF8")) {
            return naoSuportado(CategoriaDoArquivo.IMAGEM, "image/gif", "imagem GIF");
        }
        if (texto(b, n, 0, "BM")) {
            return naoSuportado(CategoriaDoArquivo.IMAGEM, "image/bmp", "imagem BMP");
        }
        if (comeca(b, n, 'I', 'I', 0x2A, 0x00) || comeca(b, n, 'M', 'M', 0x00, 0x2A)) {
            return naoSuportado(CategoriaDoArquivo.IMAGEM, "image/tiff", "imagem TIFF");
        }
        if (texto(b, n, 0, "#!AMR")) {
            return naoSuportado(CategoriaDoArquivo.AUDIO, "audio/amr", "áudio AMR");
        }
        if (comeca(b, n, 'P', 'K', 0x03, 0x04)) {
            return naoSuportado(CategoriaDoArquivo.OUTRO, "application/zip", descricaoDoZip(extensao));
        }
        if (comeca(b, n, 0xD0, 0xCF, 0x11, 0xE0)) {
            return naoSuportado(CategoriaDoArquivo.OUTRO, "application/x-ole-storage", "documento do Office antigo");
        }
        if (EXTENSOES_DE_TEXTO.contains(extensao) && ehTexto(b, n)) {
            return ok(CategoriaDoArquivo.DOCUMENTO, "text/plain");
        }
        return naoSuportado(CategoriaDoArquivo.OUTRO, null, "tipo de arquivo desconhecido");
    }

    private static TipoDetectado isoBmff(String marca, String extensao) {
        if (MARCAS_HEIC.contains(marca)) {
            return ok(CategoriaDoArquivo.IMAGEM, "image/heic");
        }
        if (MARCAS_HEIF.contains(marca)) {
            return ok(CategoriaDoArquivo.IMAGEM, "image/heif");
        }
        if (MARCAS_M4A.contains(marca) || EXTENSOES_DE_AUDIO_MP4.contains(extensao)) {
            return ok(CategoriaDoArquivo.AUDIO, "audio/mp4");
        }
        if (marca.equals("qt  ")) {
            return ok(CategoriaDoArquivo.VIDEO, "video/quicktime");
        }
        if (MARCAS_3GP.contains(marca)) {
            return ok(CategoriaDoArquivo.VIDEO, "video/3gpp");
        }
        return ok(CategoriaDoArquivo.VIDEO, "video/mp4");
    }

    private static String descricaoDoZip(String extensao) {
        return switch (extensao == null ? "" : extensao) {
            case "docx" -> "documento do Word";
            case "xlsx" -> "planilha do Excel";
            case "pptx" -> "apresentação do PowerPoint";
            default -> "arquivo compactado";
        };
    }

    /** Texto (UTF-8 ou Latin-1, como o Bloco de Notas grava): só os bytes nulos indicam arquivo binário. */
    private static boolean ehTexto(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static TipoDetectado ok(CategoriaDoArquivo categoria, String tipo) {
        return new TipoDetectado(categoria, tipo, true, null);
    }

    private static TipoDetectado naoSuportado(CategoriaDoArquivo categoria, String tipo, String descricao) {
        return new TipoDetectado(categoria, tipo, false, descricao);
    }

    private static boolean comeca(byte[] b, int n, int... esperado) {
        if (n < esperado.length) {
            return false;
        }
        for (int i = 0; i < esperado.length; i++) {
            if ((b[i] & 0xFF) != esperado[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean texto(byte[] b, int n, int inicio, String esperado) {
        return n >= inicio + esperado.length() && ascii(b, inicio, esperado.length()).equals(esperado);
    }

    private static String ascii(byte[] b, int inicio, int tamanho) {
        return new String(b, inicio, Math.min(tamanho, b.length - inicio), StandardCharsets.ISO_8859_1);
    }

    static String extensao(String nome) {
        if (nome == null) {
            return "";
        }
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 || ponto == nome.length() - 1 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }
}
