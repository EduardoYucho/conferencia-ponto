package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.Locale;
import java.util.Optional;

/** O que a pessoa pede para gerar a partir do atendimento. */
public enum TipoDeSaida {
    RESUMO("Resumo do atendimento", true),
    DEV_ERRO("Desenvolvimento: erro", false),
    DEV_ALTERACAO("Desenvolvimento: alteração", false);

    private final String rotulo;
    /** As de desenvolvimento chegam na etapa seguinte. */
    private final boolean disponivel;

    TipoDeSaida(String rotulo, boolean disponivel) {
        this.rotulo = rotulo;
        this.disponivel = disponivel;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String rotulo() {
        return rotulo;
    }

    public boolean disponivel() {
        return disponivel;
    }

    public static Optional<TipoDeSaida> doCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        for (TipoDeSaida tipo : values()) {
            if (tipo.codigo().equalsIgnoreCase(codigo.strip())) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
