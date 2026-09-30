package br.com.conferenciaponto.infrastructure.importacao;

import java.io.IOException;
import java.nio.file.Path;

/** O arquivo ainda está sendo gravado (download em andamento) ou travado por outro processo. */
public class ArquivoEmUsoException extends IOException {

    public ArquivoEmUsoException(Path arquivo, String motivo) {
        super("%s: %s".formatted(arquivo.getFileName(), motivo));
    }
}
