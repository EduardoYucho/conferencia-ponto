package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * O tipo de um arquivo do atendimento, pela extensão (o download confere depois pelos primeiros bytes). Word,
 * Excel e compactados ficam como "outro": o Gemini não lê esses formatos diretamente.
 */
public enum CategoriaDoArquivo {
    IMAGEM("image/", "jpg:jpeg", "jpeg:jpeg", "png:png", "gif:gif", "webp:webp", "bmp:bmp", "heic:heic", "heif:heif",
            "tif:tiff", "tiff:tiff"),
    AUDIO("audio/", "oga:ogg", "ogg:ogg", "opus:ogg", "mp3:mpeg", "m4a:mp4", "wav:wav", "aac:aac", "amr:amr",
            "weba:webm", "flac:flac"),
    VIDEO("video/", "mp4:mp4", "mov:quicktime", "avi:x-msvideo", "mkv:x-matroska", "3gp:3gpp", "webm:webm",
            "m4v:mp4", "wmv:x-ms-wmv", "mpeg:mpeg", "mpg:mpeg"),
    DOCUMENTO("", "pdf:application/pdf", "txt:text/plain"),
    OUTRO("");

    private static final Map<String, CategoriaDoArquivo> CATEGORIAS = new HashMap<>();
    private static final Map<String, String> TIPOS_DE_MIDIA = new HashMap<>();

    static {
        for (CategoriaDoArquivo categoria : values()) {
            for (String par : categoria.extensoes) {
                String extensao = par.substring(0, par.indexOf(':'));
                CATEGORIAS.put(extensao, categoria);
                TIPOS_DE_MIDIA.put(extensao, categoria.prefixo + par.substring(par.indexOf(':') + 1));
            }
        }
    }

    private final String prefixo;
    private final String[] extensoes;

    CategoriaDoArquivo(String prefixo, String... extensoes) {
        this.prefixo = prefixo;
        this.extensoes = extensoes;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CategoriaDoArquivo doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }

    public static CategoriaDoArquivo porExtensao(String extensao) {
        return extensao == null ? OUTRO : CATEGORIAS.getOrDefault(extensao.toLowerCase(Locale.ROOT), OUTRO);
    }

    /** O tipo de mídia esperado para a extensão (ex.: oga → audio/ogg), ou null quando não se sabe. */
    public static String tipoDeMidia(String extensao) {
        return extensao == null ? null : TIPOS_DE_MIDIA.get(extensao.toLowerCase(Locale.ROOT));
    }
}
