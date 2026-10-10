package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.util.Locale;

/** Um item da conversa é uma mensagem (de um lado) ou um evento do sistema (início, transferência, fim...). */
public enum TipoDeItem {
    MENSAGEM,
    EVENTO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TipoDeItem doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }
}
