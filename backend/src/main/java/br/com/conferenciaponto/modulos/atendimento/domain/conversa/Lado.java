package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.util.Locale;

/** De que lado da conversa veio a mensagem: no PDF do Digisac, o cliente fica à esquerda; atendente e bot, à direita. */
public enum Lado {
    CLIENTE,
    ATENDENTE;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Lado doCodigo(String codigo) {
        return codigo == null ? null : valueOf(codigo.toUpperCase(Locale.ROOT));
    }
}
