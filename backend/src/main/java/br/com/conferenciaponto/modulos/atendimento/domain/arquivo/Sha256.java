package br.com.conferenciaponto.modulos.atendimento.domain.arquivo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** O sha-256 de um arquivo (identifica o conteúdo: a análise guardada não é refeita para o mesmo arquivo). */
public final class Sha256 {

    private Sha256() {
    }

    public static String de(Path arquivo) {
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            MessageDigest resumo = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int lidos;
            while ((lidos = entrada.read(buffer)) != -1) {
                resumo.update(buffer, 0, lidos);
            }
            return HexFormat.of().formatHex(resumo.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo para o sha-256", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
