package br.com.conferenciaponto.modulos.atendimento.domain.analise;

import java.util.Locale;

/** A análise de um arquivo como a tela mostra. */
public enum SituacaoDaAnalise {
    /** O arquivo está pronto, mas a análise ainda não foi pedida (falta clicar em Processar). */
    PENDENTE,
    /** Na fila (ou esperando o arquivo ser baixado, ou pausada). */
    NA_FILA,
    ANALISANDO,
    PRONTA,
    FALHOU;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }
}
