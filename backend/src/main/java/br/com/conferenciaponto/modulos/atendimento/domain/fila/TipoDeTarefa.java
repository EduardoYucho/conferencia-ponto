package br.com.conferenciaponto.modulos.atendimento.domain.fila;

import java.util.Locale;

/** O que uma tarefa da fila faz (uma tarefa por arquivo e por etapa, ou por atendimento/saída). */
public enum TipoDeTarefa {
    BAIXAR,
    ANALISAR,
    LINHA_DO_TEMPO,
    REDIGIR,
    PREPARAR_IMAGENS;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TipoDeTarefa doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }
}
