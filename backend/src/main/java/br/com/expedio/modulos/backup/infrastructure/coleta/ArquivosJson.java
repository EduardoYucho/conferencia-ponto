package br.com.expedio.modulos.backup.infrastructure.coleta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Grava JSON sem deixar arquivo pela metade: escreve ao lado e troca de uma vez. */
public final class ArquivosJson {

    private ArquivosJson() {
    }

    public static void gravar(ObjectMapper json, Path arquivo, Object conteudo) {
        try {
            Files.createDirectories(arquivo.getParent());
            Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temporario.toFile(), conteudo);
            try {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException semAtomico) {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui gravar " + arquivo, e);
        }
    }
}
