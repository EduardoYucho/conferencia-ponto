package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.Locale;

/**
 * Pendente (esperando as análises ou a vez na fila), redigindo, pronta, falhou ou confirmada (enviada para a base,
 * numa etapa seguinte).
 */
public enum SituacaoDaSaida {
    PENDENTE, REDIGINDO, PRONTA, FALHOU, CONFIRMADA;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SituacaoDaSaida doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }

    /** Ainda vai ser redigida. */
    public boolean emAndamento() {
        return this == PENDENTE || this == REDIGINDO;
    }

    /** Tem texto. */
    public boolean comTexto() {
        return this == PRONTA || this == CONFIRMADA;
    }
}
