package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.Locale;
import java.util.Optional;

/** O que a pessoa pede para gerar a partir do atendimento. */
public enum TipoDeSaida {
    RESUMO("Resumo do atendimento", true),
    DEV_ERRO("Desenvolvimento: erro", true),
    DEV_ALTERACAO("Desenvolvimento: alteração", true);

    private final String rotulo;
    /** Um tipo pode existir no banco antes de a versão do sistema saber redigi-lo. */
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

    /** Os chamados para o desenvolvimento têm cabeçalho, rodapé e os campos que a pessoa informa. */
    public boolean paraODesenvolvimento() {
        return this != RESUMO;
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
