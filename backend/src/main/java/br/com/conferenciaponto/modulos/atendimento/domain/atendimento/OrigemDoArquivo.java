package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.util.Locale;
import java.util.Optional;

/** De onde veio o arquivo: anexo da conversa (baixado pelo link do PDF) ou enviado pela pessoa. */
public enum OrigemDoArquivo {
    ANEXO_CONVERSA("anexo"),
    LIGACAO("ligacao"),
    VIDEO("video"),
    PRINT_EXTRA("print");

    /** Começo do nome do arquivo em disco. */
    private final String prefixo;

    OrigemDoArquivo(String prefixo) {
        this.prefixo = prefixo;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String prefixo() {
        return prefixo;
    }

    public static Optional<OrigemDoArquivo> doCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        for (OrigemDoArquivo origem : values()) {
            if (origem.codigo().equalsIgnoreCase(codigo.strip())) {
                return Optional.of(origem);
            }
        }
        return Optional.empty();
    }
}
