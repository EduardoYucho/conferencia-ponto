package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import java.util.Locale;
import java.util.Optional;

/**
 * Onde o caso está: resolvido no próprio suporte ou enviado ao desenvolvimento, e depois corrigido (numa versão) ou
 * recusado. Atualizada à mão, com histórico.
 */
public enum SituacaoDoRegistro {
    RESOLVIDO_SUPORTE("Resolvido no suporte"),
    ENVIADO_DESENVOLVIMENTO("Enviado ao desenvolvimento"),
    CORRIGIDO("Corrigido"),
    NAO_SERA_FEITO("Não será feito");

    private final String rotulo;

    SituacaoDoRegistro(String rotulo) {
        this.rotulo = rotulo;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String rotulo() {
        return rotulo;
    }

    public static Optional<SituacaoDoRegistro> doCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        for (SituacaoDoRegistro situacao : values()) {
            if (situacao.codigo().equalsIgnoreCase(codigo.strip())) {
                return Optional.of(situacao);
            }
        }
        return Optional.empty();
    }
}
