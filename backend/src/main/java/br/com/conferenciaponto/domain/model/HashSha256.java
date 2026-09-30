package br.com.conferenciaponto.domain.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 em hexadecimal minúsculo (64 caracteres): identidade e integridade dos comprovantes. */
public final class HashSha256 {

    private HashSha256() {
    }

    public static String de(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
