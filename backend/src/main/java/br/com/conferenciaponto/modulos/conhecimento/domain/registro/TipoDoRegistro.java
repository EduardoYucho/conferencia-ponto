package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import java.util.Locale;
import java.util.Optional;

/** O que o registro conta: uma dúvida resolvida, um erro ou um pedido de alteração. */
public enum TipoDoRegistro {
    DUVIDA("Dúvida"), ERRO("Erro"), ALTERACAO("Alteração");

    private final String rotulo;

    TipoDoRegistro(String rotulo) {
        this.rotulo = rotulo;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String rotulo() {
        return rotulo;
    }

    public static Optional<TipoDoRegistro> doCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        for (TipoDoRegistro tipo : values()) {
            if (tipo.codigo().equalsIgnoreCase(codigo.strip())) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
